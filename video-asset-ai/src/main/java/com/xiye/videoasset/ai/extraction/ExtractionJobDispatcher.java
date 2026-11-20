package com.xiye.videoasset.ai.extraction;

import com.xiye.videoasset.ai.config.RabbitTopology;
import com.xiye.videoasset.ai.extraction.ExtractionTaskMapper.PublishCandidate;
import com.xiye.videoasset.api.extraction.ExtractionJobMessage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ExtractionJobDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(ExtractionJobDispatcher.class);
    private final ExtractionTaskMapper tasks;
    private final RabbitTemplate rabbit;

    ExtractionJobDispatcher(ExtractionTaskMapper tasks, RabbitTemplate rabbit) {
        this.tasks = tasks;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelay = 1000, scheduler = "extractionJobScheduler")
    void dispatch() {
        int expired = tasks.expirePublish();
        if (expired > 0) {
            LOG.warn("素材任务投递期限已到 errorCode=QUEUE_UNAVAILABLE count={}", expired);
        }
        for (PublishCandidate candidate : tasks.due()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (tasks.claimPublish(candidate.id(), candidate.attempts()) > 0) {
                publish(candidate);
            }
        }
    }

    private void publish(PublishCandidate candidate) {
        CorrelationData correlation = new CorrelationData();
        long taskId = candidate.id();
        long started = System.nanoTime();
        int attempt = candidate.attempts() + 1;
        try {
            rabbit.convertAndSend(RabbitTopology.TASK_EXCHANGE, RabbitTopology.EXTRACTION_ROUTING_KEY,
                    new ExtractionJobMessage(taskId), correlation);
            CorrelationData.Confirm confirmation = correlation.getFuture().get(3, TimeUnit.SECONDS);
            if (confirmation.isAck() && correlation.getReturned() == null) {
                // ACK 只表示已入队。保留低频补投，覆盖消费者异常退出或消息进入死信。
                tasks.confirmPublish(taskId, attempt);
                LOG.info("RabbitMQ 素材任务已确认 taskId={} attempt={} durationMs={}", taskId, attempt, elapsed(started));
            } else {
                LOG.warn("RabbitMQ 素材任务投递未确认 taskId={} attempt={}", taskId, attempt);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (AmqpException | ExecutionException | TimeoutException exception) {
            LOG.warn("RabbitMQ 素材任务投递异常 taskId={} attempt={} durationMs={} exceptionType={}",
                    taskId, attempt, elapsed(started), exception.getClass().getSimpleName());
        }
    }

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
