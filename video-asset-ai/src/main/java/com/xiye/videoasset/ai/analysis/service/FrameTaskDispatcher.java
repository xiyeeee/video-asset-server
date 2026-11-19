package com.xiye.videoasset.ai.analysis.service;

import com.xiye.videoasset.ai.analysis.persistence.FrameTaskRepository;
import com.xiye.videoasset.ai.config.RabbitTopology;
import com.xiye.videoasset.api.ai.FrameTaskMessage;
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
class FrameTaskDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(FrameTaskDispatcher.class);
    private final FrameTaskRepository tasks;
    private final RabbitTemplate rabbit;

    FrameTaskDispatcher(FrameTaskRepository tasks, RabbitTemplate rabbit) {
        this.tasks = tasks;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelay = 1500)
    void dispatch() {
        tasks.expire();
        for (FrameTaskMessage message : tasks.due()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (tasks.claimDispatch(message)) {
                publish(message);
            }
        }
    }

    private void publish(FrameTaskMessage message) {
        CorrelationData correlation = new CorrelationData();
        try {
            // 待投递状态保存在数据库，发布失败或进程退出后可以按下一投递时间恢复。
            rabbit.convertAndSend(RabbitTopology.TASK_EXCHANGE, RabbitTopology.TASK_ROUTING_KEY, message, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(3, TimeUnit.SECONDS);
            if (confirm.isAck() && correlation.getReturned() == null) {
                tasks.confirmDispatch(message);
                LOG.info("首帧任务已投递 taskId={}", message.taskId());
            } else {
                LOG.warn("首帧任务投递未确认 taskId={}", message.taskId());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (AmqpException | ExecutionException | TimeoutException exception) {
            LOG.warn("首帧任务投递暂不可用 taskId={} exceptionType={}",
                    message.taskId(), exception.getClass().getSimpleName());
        }
    }
}
