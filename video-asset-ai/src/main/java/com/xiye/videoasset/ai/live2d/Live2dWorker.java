package com.xiye.videoasset.ai.live2d;

import com.xiye.videoasset.ai.live2d.Live2dArchive.ModelFile;
import com.xiye.videoasset.ai.live2d.Live2dPython.Artifact;
import com.xiye.videoasset.ai.live2d.Live2dPython.Reply;
import com.xiye.videoasset.ai.live2d.Live2dPython.Snapshot;
import com.xiye.videoasset.ai.live2d.Live2dTask.Status;
import com.xiye.videoasset.ai.storage.AiObjectStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class Live2dWorker {
    private static final Logger LOG = LoggerFactory.getLogger(Live2dWorker.class);
    private static final int POLL_SECONDS = 3;
    private final Live2dMapper tasks;
    private final Live2dImages images;
    private final Live2dPython python;
    private final Live2dArchive archives;
    private final AiObjectStorage storage;
    private final Live2dAssets assets;
    private final TransactionTemplate transaction;

    Live2dWorker(
            Live2dMapper tasks, Live2dImages images, Live2dPython python, Live2dArchive archives,
            AiObjectStorage storage, Live2dAssets assets, TransactionTemplate transaction
    ) {
        this.tasks = tasks;
        this.images = images;
        this.python = python;
        this.archives = archives;
        this.storage = storage;
        this.assets = assets;
        this.transaction = transaction;
    }

    @Scheduled(fixedDelay = 1500, scheduler = "live2dScheduler")
    void tick() {
        int expired = tasks.expire();
        if (expired > 0) {
            LOG.warn("Live2D 任务到达期限 count={}", expired);
        }
        Live2dTask task = tasks.due();
        if (task == null || tasks.claim(task.id(), task.leaseVersion()) == 0) {
            return;
        }
        int lease = task.leaseVersion() + 1;
        try {
            if (task.artifactRef() == null) {
                poll(task, lease);
            } else {
                importPackage(task, lease);
            }
        } catch (Live2dFailure failure) {
            fail(task, lease, failure.getMessage(), task.stage());
        } catch (IOException | DataAccessException exception) {
            LOG.warn("Live2D 同步暂不可用 taskId={} projectId={} exceptionType={}",
                    task.id(), task.projectId(), exception.getClass().getSimpleName());
            tasks.schedule(task.id(), lease, POLL_SECONDS);
        }
    }

    private void poll(Live2dTask task, int lease) throws IOException {
        Reply reply = python.get(task);
        if (reply.missing()) {
            if (task.submissionCount() >= 3) {
                fail(task, lease, "PYTHON_TASK_LOST", task.stage());
                return;
            }
            String reference = images.prepare(task, tasks.source(task.projectId(), task.sourceAssetId()));
            if (tasks.submitted(task.id(), lease) == 0) {
                return;
            }
            // 提交超时后下一轮先查询同一 ID，Python 查无任务才安全重发原请求。
            reply = python.submit(task, reference);
        }
        if (reply.transientFailure()) {
            tasks.schedule(task.id(), lease, POLL_SECONDS);
            return;
        }
        Snapshot snapshot = reply.snapshot();
        if (snapshot == null) {
            fail(task, lease, reply.errorCode(), task.stage());
            return;
        }
        if (snapshot.error() != null) {
            fail(task, lease, snapshot.error().code(),
                    snapshot.error().stage() == null ? snapshot.stage() : snapshot.error().stage());
            return;
        }
        Artifact artifact = snapshot.result();
        // Python 完成后保存引用。Java 完整发布资源前继续对外报告 RUNNING。
        Status state = artifact == null ? snapshot.status() : Status.RUNNING;
        tasks.progress(task.id(), lease, state, snapshot.stage(),
                artifact == null ? null : artifact.manifestPath(), artifact == null ? null : artifact.artifactRef(),
                artifact == null ? POLL_SECONDS : 0);
        if (task.status() != state || artifact != null) {
            LOG.info("Live2D 任务状态更新 taskId={} projectId={} status={} stage={}",
                    task.id(), task.projectId(), state, snapshot.stage());
        }
    }

    private void importPackage(Live2dTask task, int lease) throws IOException {
        long started = System.nanoTime();
        Path zip = archives.locate(task);
        Path temp = Files.createTempDirectory("video-asset-live2d-");
        try {
            Map<String, ModelFile> files = archives.unpack(zip, temp, task.manifestPath());
            String root = Live2dAssets.root(task.projectId(), task.id());
            for (Map.Entry<String, ModelFile> entry : files.entrySet()) {
                if (tasks.schedule(task.id(), lease, 120) == 0) {
                    return;
                }
                ModelFile file = entry.getValue();
                storage.put(Live2dAssets.BUCKET, root + "files/" + entry.getKey(),
                        file.contentType(), file.path(), file.size());
            }
            if (tasks.schedule(task.id(), lease, 120) == 0) {
                return;
            }
            long zipBytes = Files.size(zip);
            storage.put(Live2dAssets.BUCKET, root + "model.zip", "application/zip", zip, zipBytes);
            Boolean committed = transaction.execute(tx -> {
                // 固定对象键允许重新导入；任务行锁使同一任务只登记一个模型资产。
                if (tasks.lock(task.id(), lease) == null) {
                    return false;
                }
                long model = assets.insert(task.projectId(), Live2dAssets.MODEL, root + "model.zip", "model.zip",
                        "application/zip", zipBytes);
                for (Map.Entry<String, ModelFile> entry : files.entrySet()) {
                    tasks.file(model, entry.getKey(), entry.getValue().contentType(), entry.getValue().size());
                }
                if (tasks.succeed(task.id(), lease, model) == 0) {
                    tx.setRollbackOnly();
                    return false;
                }
                return true;
            });
            if (Boolean.TRUE.equals(committed)) {
                LOG.info("Live2D 模型已发布 taskId={} projectId={} fileCount={} durationMs={}",
                        task.id(), task.projectId(), files.size(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        } finally {
            clean(temp, task.id());
        }
    }

    private static void clean(Path temp, String taskId) {
        try (Stream<Path> files = Files.walk(temp)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException exception) {
            LOG.warn("Live2D 解包临时文件清理失败 taskId={}", taskId);
        }
    }

    private void fail(Live2dTask task, int lease, String code, String stage) {
        if (tasks.fail(task.id(), lease, code, stage) > 0) {
            LOG.warn("Live2D 任务失败 taskId={} projectId={} stage={} errorCode={}",
                    task.id(), task.projectId(), stage, code);
        }
    }
}
