package com.xiye.videoasset.ai.analysis.service;

import com.xiye.videoasset.ai.analysis.persistence.FrameTaskRepository;
import com.xiye.videoasset.ai.analysis.persistence.FrameTaskRepository.Work;
import com.xiye.videoasset.ai.config.RabbitTopology;
import com.xiye.videoasset.api.ai.FrameTaskMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class FrameTaskWorker {
    private static final Logger LOG = LoggerFactory.getLogger(FrameTaskWorker.class);
    private final FrameTaskRepository tasks;
    private final FrameStorage storage;
    private final FirstFrameExtractor extractor;
    private final TransactionTemplate transaction;

    FrameTaskWorker(FrameTaskRepository tasks, FrameStorage storage,
                    FirstFrameExtractor extractor, TransactionTemplate transaction) {
        this.tasks = tasks;
        this.storage = storage;
        this.extractor = extractor;
        this.transaction = transaction;
    }

    @RabbitListener(queues = RabbitTopology.TASK_QUEUE)
    void process(FrameTaskMessage message) {
        // 重复投递只有一个消费者能从待处理切换到执行中。
        if (!tasks.claimWork(message)) {
            LOG.info("跳过已领取的首帧任务 taskId={}", message.taskId());
            return;
        }

        long taskId = message.taskId();
        long attempt = message.previousAttempt() + 1;
        try {
            Work work = tasks.source(taskId).orElseThrow(() -> new FrameProcessingException("SOURCE_NOT_FOUND"));
            LOG.info("开始首帧提取 taskId={} episodeId={} attempt={}", taskId, work.episodeId(), attempt);
            processFrame(taskId, attempt, work);
        } catch (FrameProcessingException failure) {
            tasks.fail(taskId, attempt, failure.code());
            LOG.warn("首帧提取失败 taskId={} attempt={} errorCode={}", taskId, attempt, failure.code());
        } catch (RuntimeException failure) {
            tasks.fail(taskId, attempt, "TASK_INTERNAL_ERROR");
            LOG.error("首帧任务异常 taskId={} attempt={} exceptionType={}",
                    taskId, attempt, failure.getClass().getSimpleName());
        }
    }

    private void processFrame(long taskId, long attempt, Work work) {
        Path folder;
        try {
            folder = Files.createTempDirectory("video-asset-frame-");
        } catch (IOException exception) {
            throw new FrameProcessingException("WORKSPACE_UNAVAILABLE", exception);
        }
        Path video = folder.resolve("source.mp4");
        Path image = folder.resolve("first-frame.jpg");
        try {
            storage.download(work, video);
            extractor.extract(video, image);
            String key = "projects/" + work.projectId() + "/episodes/" + work.episodeId()
                    + "/frames/" + taskId + "/" + attempt + ".jpg";
            long size = storage.put(key, image);

            // 每次执行有独立对象键，过期的执行不能覆盖新重试的状态或素材。
            Boolean committed = transaction.execute(status -> {
                if (!tasks.lockRunning(taskId, attempt)) {
                    return false;
                }
                long assetId = tasks.insertFrame(work, FrameStorage.BUCKET, key, size);
                tasks.succeed(taskId, assetId);
                return true;
            });
            if (Boolean.TRUE.equals(committed)) {
                LOG.info("首帧提取完成 taskId={} episodeId={} attempt={} assetCount=1",
                        taskId, work.episodeId(), attempt);
            } else {
                storage.discardUnattached(key, taskId);
            }
        } finally {
            cleanup(taskId, image, video, folder);
        }
    }

    private static void cleanup(long taskId, Path... files) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException exception) {
                LOG.warn("清理首帧临时文件失败 taskId={}", taskId);
            }
        }
    }
}
