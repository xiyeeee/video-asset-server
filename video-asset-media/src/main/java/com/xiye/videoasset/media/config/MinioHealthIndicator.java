package com.xiye.videoasset.media.config;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import java.util.List;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
class MinioHealthIndicator implements HealthIndicator {
    private final MinioClient minio;
    private final List<String> buckets = List.of(
            "video-original", "video-frames", "video-audio", "video-assets", "video-exports");

    MinioHealthIndicator(MinioClient minio) {
        this.minio = minio;
    }

    @Override
    public Health health() {
        try {
            for (String bucket : buckets) {
                if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    return Health.down().build();
                }
            }
            return Health.up().build();
        } catch (Exception exception) {
            return Health.down().build();
        }
    }
}
