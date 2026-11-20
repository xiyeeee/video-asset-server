package com.xiye.videoasset.ai.characters.service;

import com.xiye.videoasset.ai.characters.model.PythonCharacterTask;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskEntity;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper.SourceVideo;
import com.xiye.videoasset.ai.characters.service.PythonCharacterClient.Reply;
import com.xiye.videoasset.ai.storage.AiObjectStorage.SourceSizeInvalid;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class CharacterTaskSync {
    private static final int MAX_SUBMISSIONS = 3;
    private static final Logger LOG = LoggerFactory.getLogger(CharacterTaskSync.class);

    private final CharacterTaskMapper tasks;
    private final PythonCharacterClient python;
    private final CharacterSharedFiles files;
    private final CharacterResultImporter importer;
    private final CharacterExtractionProperties properties;

    CharacterTaskSync(
            CharacterTaskMapper tasks, PythonCharacterClient python, CharacterSharedFiles files,
            CharacterResultImporter importer, CharacterExtractionProperties properties
    ) {
        this.tasks = tasks;
        this.python = python;
        this.files = files;
        this.importer = importer;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 1000, scheduler = "characterTaskScheduler")
    void tick() {
        int expired = tasks.expire();
        if (expired > 0) {
            LOG.warn("人物提取任务达到处理期限 errorCode=CHARACTER_TASK_TIMEOUT count={}", expired);
        }
        CharacterTaskEntity task = tasks.due();
        if (task == null || tasks.claim(task.id(), task.leaseVersion()) == 0) {
            return;
        }

        long lease = task.leaseVersion() + 1;
        try {
            synchronize(task, lease);
        } catch (SourceSizeInvalid exception) {
            fail(task, lease, "SOURCE_SIZE_INVALID");
        } catch (CharacterFailure failure) {
            if (fail(task, lease, failure.code()) && task.manifestRef() != null) {
                files.removeInput(task.id());
            }
        } catch (IOException exception) {
            if (task.manifestRef() == null) {
                LOG.warn("准备人物提取源文件失败 taskId={} errorCode=SHARED_INPUT_UNAVAILABLE", task.id());
            }
            pollLater(task, lease);
        } catch (DataAccessException exception) {
            if (task.manifestRef() == null) {
                LOG.warn("同步人物任务状态失败 taskId={} errorCode=CHARACTER_DATABASE_UNAVAILABLE", task.id());
            }
            pollLater(task, lease);
        }
    }

    private void synchronize(CharacterTaskEntity task, long lease) throws IOException {
        if (task.manifestRef() != null) {
            if (importer.importResult(task, lease, source(task))) {
                files.removeInput(task.id());
            }
            return;
        }

        // 提交超时可能已经受理，恢复时总先查询原 ID。Python 重启后也沿用此 ID 和原参数。
        Reply reply = python.poll(task.id(), task.attemptCount());
        if (reply.unknownTask()) {
            if (task.attemptCount() >= MAX_SUBMISSIONS) {
                fail(task, lease, "PYTHON_TASK_LOST");
                return;
            }
            String reference = files.prepare(task.id(), source(task));
            if (tasks.recordSubmission(task.id(), lease) == 0) {
                return;
            }
            reply = python.submit(task.id(), task.attemptCount() + 1, reference, task.intervalSeconds());
        }
        accept(task, lease, reply);
    }

    private void accept(CharacterTaskEntity task, long lease, Reply reply) {
        if (reply.transientFailure()) {
            pollLater(task, lease);
            return;
        }
        PythonCharacterTask state = reply.state();
        if (state == null) {
            fail(task, lease, reply.errorCode());
            return;
        }

        String localStatus = state.status() == PythonCharacterTask.Status.QUEUED ? "PENDING" : "RUNNING";
        String stage = state.stage() == null ? null : state.stage().name();
        if (state.status() == PythonCharacterTask.Status.FAILED) {
            // 失败和阶段同一次写入，避免恢复轮询时遗失 Python 已确认的终态。
            String code = PythonCharacterClient.safeCode(state.error().code());
            int failed = tasks.failFromPython(task.id(), lease, code, stage, state.progress().frameCount(),
                    state.progress().detectedCharacterCount());
            if (failed != 0) {
                files.removeInput(task.id());
            }
            return;
        }
        PythonCharacterTask.Result result = state.result();
        // 先保存成功清单引用。即使 Python 随后重启，Java 仍可从已发布的文件完成导入。
        int updated = tasks.progress(task.id(), lease, localStatus, stage, state.progress().frameCount(),
                state.progress().detectedCharacterCount(), result == null ? null : result.manifestRef(),
                result == null ? null : result.characterAssetCount());
        if (updated == 0) {
            return;
        }
        if (result != null) {
            tasks.schedule(task.id(), lease, 0);
        } else {
            pollLater(task, lease);
        }
    }

    private SourceVideo source(CharacterTaskEntity task) {
        SourceVideo source = tasks.source(task.episodeId(), task.sourceAssetId());
        if (source == null) {
            throw new CharacterFailure("SOURCE_VIDEO_NOT_FOUND");
        }
        return source;
    }

    private void pollLater(CharacterTaskEntity task, long lease) {
        tasks.schedule(task.id(), lease, properties.pollInterval().toSeconds());
    }

    private boolean fail(CharacterTaskEntity task, long lease, String errorCode) {
        if (tasks.fail(task.id(), lease, errorCode) == 0) {
            return false;
        }
        LOG.warn("人物提取结束 taskId={} episodeId={} status=FAILED errorCode={}",
                task.id(), task.episodeId(), errorCode);
        return true;
    }
}
