package com.xiye.videoasset.common.redis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

@Configuration(proxyBeanMethods = false)
public class RedisConfiguration {
    @Bean
    RedisTemplate<String, JsonNode> redisTemplate(RedisConnectionFactory factory, ObjectMapper mapper) {
        RedisTemplate<String, JsonNode> template = new RedisTemplate<>();
        Jackson2JsonRedisSerializer<JsonNode> valueSerializer =
                new Jackson2JsonRedisSerializer<>(mapper, JsonNode.class);
        RedisSerializer<String> keySerializer = RedisSerializer.string();

        template.setConnectionFactory(factory);
        template.setEnableDefaultSerializer(false);
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        return template;
    }
}
