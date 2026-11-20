package com.xiye.videoasset.ai.extraction;

import com.xiye.videoasset.ai.characters.service.CharacterExtractionProperties;
import com.xiye.videoasset.ai.config.RabbitTopology;
import com.xiye.videoasset.ai.extraction.ExtractionTaskMapper.Step;
import com.xiye.videoasset.api.extraction.ExtractionJob;
import com.xiye.videoasset.api.extraction.ExtractionJobMessage;
import com.xiye.videoasset.api.extraction.ExtractionType;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
class ExtractionJobConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(ExtractionJobConsumer.class);
    private final ExtractionTaskMapper tasks;
    private final CharacterExtractionProperties properties;

    ExtractionJobConsumer(ExtractionTaskMapper tasks, CharacterExtractionProperties properties) {
        this.tasks = tasks;
        this.properties = properties;
    }

    @RabbitListener(queues = RabbitTopology.EXTRACTION_QUEUE)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void consume(ExtractionJobMessage message) {
        ExtractionJob job = tasks.lock(message.taskId());
        if (job == null || job.dispatchedAt() != null || job.dispatchFailedAt() != null) {
            LOG.info("跳过重复或已失效的素材任务消息 taskId={}", message.taskId());
            return;
        }
        // 先锁主任务，消息重复或多实例消费只关联一次。实际 Python 执行仍按子任务租约领取。
        for (Step step : tasks.steps(job.id())) {
            if (step.workerTaskId() == null) {
                long workerId = resolveWorker(job, step);
                tasks.bind(job.id(), step.type(), workerId);
                LOG.info("RabbitMQ 素材任务已消费 taskId={} sourceTaskId={} projectId={} episodeId={} extractType={}",
                        job.id(), workerId, job.projectId(), job.episodeId(), step.type());
            }
        }
        tasks.markDispatched(job.id());
    }

    private long resolveWorker(ExtractionJob job, Step step) {
        String workerType = switch (step.type()) {
            case CHARACTER -> "CHARACTER_EXTRACTION";
            case AUDIO -> "AUDIO_EXTRACTION";
            default -> throw new IllegalStateException("无可用提取处理器");
        };
        if (!job.regenerate()) {
            Long existing = tasks.reusableWorker(job.episodeId(), job.sourceAssetId(), workerType);
            if (existing != null) {
                return existing;
            }
        }

        String workerKey = workerKey(job, step, workerType);
        tasks.createWorker(job.episodeId(), workerType, workerKey, job.sourceAssetId(),
                properties.frameIntervalSeconds(), properties.taskTimeout().toSeconds());
        return Objects.requireNonNull(tasks.worker(job.episodeId(), workerType, workerKey, job.sourceAssetId()));
    }

    private String workerKey(ExtractionJob job, Step step, String workerType) {
        Long failedWorker = step.retryFromWorkerId();
        if (failedWorker != null) {
            return "unified:retry:" + failedWorker;
        }
        if (job.regenerate()) {
            return "unified:regenerate:" + job.id();
        }
        failedWorker = tasks.latestFailedWorker(job.episodeId(), job.sourceAssetId(), workerType);
        if (failedWorker != null) {
            return "unified:retry:" + failedWorker;
        }
        // 无结果的同源并发任务使用同一幂等键。人物首个任务还可与原接口共用。
        return step.type() == ExtractionType.CHARACTER ? "character:v1" : "unified:source:" + job.sourceAssetId();
    }
}
