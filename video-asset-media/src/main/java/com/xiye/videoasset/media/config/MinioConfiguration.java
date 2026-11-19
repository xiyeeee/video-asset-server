package com.xiye.videoasset.media.config;

import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class MinioConfiguration {
    @Bean
    MinioClient minioClient(@Value("${app.minio.endpoint}") String endpoint,
                            @Value("${app.minio.access-key}") String accessKey,
                            @Value("${app.minio.secret-key}") String secretKey) {
        var http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(2))
                .callTimeout(Duration.ofSeconds(3))
                .build();
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).httpClient(http).build();
    }
}
