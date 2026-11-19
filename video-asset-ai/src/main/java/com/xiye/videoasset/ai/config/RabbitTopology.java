package com.xiye.videoasset.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RabbitTopology {
    static final String TASK_EXCHANGE = "video.asset.ai";
    static final String TASK_QUEUE = "video.asset.ai.task";
    static final String TASK_ROUTING_KEY = "task.requested";
    static final String DEAD_LETTER_EXCHANGE = "video.asset.ai.dlx";
    static final String DEAD_LETTER_QUEUE = "video.asset.ai.task.dead";
    static final String DEAD_LETTER_ROUTING_KEY = "task.dead";

    @Bean
    Declarables aiTaskTopology() {
        DirectExchange taskExchange = new DirectExchange(TASK_EXCHANGE);
        // 限制消息被重新投递的次数，持续失败的任务交由死信队列保留以便排查。
        Queue taskQueue = QueueBuilder.durable(TASK_QUEUE)
                .quorum()
                .deliveryLimit(3)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
        DirectExchange deadLetterExchange = new DirectExchange(DEAD_LETTER_EXCHANGE);
        Queue deadLetterQueue = QueueBuilder.durable(DEAD_LETTER_QUEUE).quorum().build();
                return new Declarables(
                taskExchange,
                taskQueue,
                deadLetterExchange,
                deadLetterQueue,
                BindingBuilder.bind(taskQueue).to(taskExchange).with(TASK_ROUTING_KEY),
                BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DEAD_LETTER_ROUTING_KEY));
    }

    @Bean
    Jackson2JsonMessageConverter rabbitMessageConverter(ObjectMapper mapper) {
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("com.xiye.videoasset.api");
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(mapper);
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }
}
