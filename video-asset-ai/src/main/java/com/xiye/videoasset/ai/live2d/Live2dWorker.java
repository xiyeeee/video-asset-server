package com.xiye.videoasset.ai.live2d;

import com.xiye.videoasset.ai.live2d.Live2dArchive.ModelFile;
import com.xiye.videoasset.ai.live2d.Live2dHistoryMapper.Published;
import com.xiye.videoasset.ai.live2d.Live2dPython.Reply;
import com.xiye.videoasset.ai.storage.AiObjectStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
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
    private static final int RETRY_SECONDS = 3;
    private final Live2dMapper tasks;
    private final Live2dImages images;
    private final Live2dPython python;
    private final Live2dArchive archives;
    private final AiObjectStorage storage;
    private final Live2dAssets assets;
    private final Live2dHistory history;
    private final Live2dPackages packages;
    private final TransactionTemplate transaction;
    private final Live2dHub hub;

    Live2dWorker(
            Live2dMapper tasks, Live2dImages images, Live2dPython python, Live2dArchive archives,
            AiObjectStorage storage, Live2dAssets assets, Live2dHistory history, Live2dPackages packages,
            TransactionTemplate transaction, Live2dHub hub
    ) {
        this.tasks = tasks;
        this.images = images;
        this.python = python;
        this.archives = archives;
        this.storage = storage;
        this.assets = assets;
        this.history = history;
        this.packages = packages;
        this.transaction = transaction;
        this.hub = hub;
    }

    @Scheduled(fixedDelay = 1500, scheduler = "live2dScheduler")
    void tick() {
        List<Live2dMapper.Notice> overdue = tasks.overdue();
        int expired = tasks.expire();
        if (expired > 0) {
            LOG.warn("Live2D 任务到达期限 count={}", expired);
            for (Live2dMapper.Notice notice : overdue) {
                hub.publish(notice.projectId(), notice.id());
            }
        }
        Live2dTask task = tasks.due();
        if (task == null || tasks.claim(task.id(), task.leaseVersion()) == 0) {
            return;
        }
        int lease = task.leaseVersion() + 1;
        try {
            if (task.artifactRef() == null) {
                submitTask(task, lease);
            } else {
                importPackage(task, lease);
            }
        } catch (Live2dFailure failure) {
            fail(task, lease, failure.getMessage(), task.stage());
        } catch (IOException | DataAccessException exception) {
            LOG.warn("Live2D 同步暂不可用 taskId={} projectId={} characterId={} exceptionType={}",
                    task.id(), task.projectId(), task.characterId(), exception.getClass().getSimpleName());
            tasks.schedule(task.id(), lease, RETRY_SECONDS);
        }
    }

    private void submitTask(Live2dTask task, int lease) throws IOException {
        if (task.submissionCount() > 0) {
            tasks.awaitPush(task.id(), lease);
            return;
        }
        String reference = images.prepare(task, tasks.source(task.projectId(), task.sourceAssetId()));
        Reply reply = python.submit(task, reference);
        if (reply.transientFailure()) {
            tasks.schedule(task.id(), lease, RETRY_SECONDS);
            return;
        }
        if (tasks.submitted(task.id(), lease) == 0 || reply.snapshot() == null) {
            fail(task, lease, reply.errorCode(), task.stage());
            return;
        }
        // 后续阶段由 Python 的 WebSocket 推送。已拿到模型包时保留导入调度。
        tasks.awaitPush(task.id(), lease);
    }

    private void importPackage(Live2dTask task, int lease) throws IOException {
        long started = System.nanoTime();
        Path temp = Files.createTempDirectory("video-asset-live2d-");
        try {
            String root = Live2dAssets.root(task.projectId(), task.id());
            String zipKey = root + "model.zip";
            Path zip = localPackage(task, zipKey, temp.resolve("upload.zip"));
            Map<String, ModelFile> files = archives.unpack(zip, Files.createDirectory(temp.resolve("files")),
                    task.manifestPath());
            if (tasks.schedule(task.id(), lease, 120) == 0) {
                return;
            }
            long zipBytes = Files.size(zip);
            // 先持久化完整包。其余文件写入失败后，原工作目录删除也可用该包重试。
            if (!zip.startsWith(temp)) {
                storage.put(Live2dAssets.BUCKET, zipKey, "application/zip", zip, zipBytes);
            }
            packages.requireSize(Live2dAssets.BUCKET, zipKey, zipBytes);
            for (Map.Entry<String, ModelFile> entry : files.entrySet()) {
                if (tasks.schedule(task.id(), lease, 120) == 0) {
                    return;
                }
                ModelFile file = entry.getValue();
                String key = root + "files/" + entry.getKey();
                storage.put(Live2dAssets.BUCKET, key, file.contentType(), file.path(), file.size());
                packages.requireSize(Live2dAssets.BUCKET, key, file.size());
            }
            Published version = transaction.execute(tx -> {
                // 固定对象键允许重新导入；任务行锁使同一任务只登记一个模型资产。
                if (tasks.lock(task.id(), lease) == null) {
                    return null;
                }
                long model = assets.insert(task.projectId(), Live2dAssets.MODEL, zipKey, "model.zip",
                        "application/zip", zipBytes);
                for (Map.Entry<String, ModelFile> entry : files.entrySet()) {
                    tasks.file(model, entry.getKey(), entry.getValue().contentType(), entry.getValue().size());
                }
                Published published = history.publish(task.id(), task.projectId(), task.sourceAssetId(),
                        task.modelName(), task.characterId(), model);
                if (tasks.succeed(task.id(), lease, model) == 0) {
                    tx.setRollbackOnly();
                    return null;
                }
                return published;
            });
            if (version != null) {
                LOG.info("Live2D 模型已发布 taskId={} projectId={} characterId={} versionId={} fileCount={} durationMs={}",
                        task.id(), task.projectId(), version.characterId(), version.id(), files.size(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                hub.publish(task.projectId(), task.id());
            }
        } finally {
            clean(temp, task.id());
        }
    }

    private Path localPackage(Live2dTask task, String key, Path target) throws IOException {
        try {
            return archives.locate(task);
        } catch (Live2dFailure failure) {
            if (!"ARTIFACT_MISSING".equals(failure.getMessage())) {
                throw failure;
            }
            Long bytes = storage.objectSize(Live2dAssets.BUCKET, key);
            if (bytes == null) {
                throw failure;
            }
            return packages.copyStored(Live2dAssets.BUCKET, key, bytes, target);
        }
    }

    static void clean(Path temp, String taskId) {
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
            LOG.warn("Live2D 任务失败 taskId={} projectId={} characterId={} stage={} errorCode={}",
                    task.id(), task.projectId(), task.characterId(), stage, code);
            hub.publish(task.projectId(), task.id());
        }
    }
}
