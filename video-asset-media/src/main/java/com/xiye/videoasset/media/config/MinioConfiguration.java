package com.xiye.videoasset.media.config;

import io.minio.MinioClient;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
class MinioConfiguration {
    private final String endpoint;
    private final String playbackEndpoint;
    private final String accessKey;
    private final String secretKey;
    private final String region;

    MinioConfiguration(
            @Value("${app.minio.endpoint}") String endpoint,
            @Value("${app.minio.playback-endpoint}") String playbackEndpoint,
            @Value("${app.minio.access-key}") String accessKey,
            @Value("${app.minio.secret-key}") String secretKey,
            @Value("${app.minio.region}") String region
    ) {
        this.endpoint = endpoint;
        this.playbackEndpoint = playbackEndpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
    }

    @Bean
    @Primary
    MinioClient minioClient() {
        return client(endpoint, new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(2))
                .callTimeout(Duration.ofSeconds(3))
                .build());
    }

    @Bean
    MinioClient episodeUploadMinioClient() {
        // 健康探针继续使用短超时，文件写入单独配置请求时限。
        return client(endpoint, new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .writeTimeout(Duration.ofSeconds(45))
                .readTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofMinutes(2))
                .build());
    }

    @Bean
    MinioClient episodePlaybackMinioClient() {
        return client(playbackEndpoint, new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(2))
                .callTimeout(Duration.ofSeconds(3))
                .build());
    }

    private MinioClient client(String serviceEndpoint, OkHttpClient httpClient) {
        return MinioClient.builder()
                .endpoint(serviceEndpoint)
                .region(region)
                .credentials(accessKey, secretKey)
                .httpClient(httpClient)
                .build();
    }
}
