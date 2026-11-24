package com.xiye.videoasset.ai.live2d;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(Live2dSettings.class)
class Live2dConfiguration {
    @Bean("live2dScheduler")
    ThreadPoolTaskScheduler live2dScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("live2d-");
        return scheduler;
    }
}
