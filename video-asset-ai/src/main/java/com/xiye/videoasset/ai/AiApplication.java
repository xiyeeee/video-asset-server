package com.xiye.videoasset.ai;

import com.xiye.videoasset.common.security.ResourceServerConfiguration;
import com.xiye.videoasset.common.redis.RedisConfiguration;
import com.xiye.videoasset.common.web.ApiExceptionHandler;
import com.xiye.videoasset.common.web.DevelopmentProbe;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({ResourceServerConfiguration.class, ApiExceptionHandler.class, DevelopmentProbe.class, RedisConfiguration.class})
public class AiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
