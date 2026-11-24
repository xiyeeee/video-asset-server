package com.xiye.videoasset.ai.storage;

import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

@Component
public class AiObjectStorage {
    public static final int PREVIEW_TTL_SECONDS = 600;
    private static final Logger LOG = LoggerFactory.getLogger(AiObjectStorage.class);

    private final MinioClient internal;
    private final MinioClient preview;
    private final long maxSourceBytes;

    AiObjectStorage(
            @Value("${app.minio.endpoint}") String endpoint,
            @Value("${app.minio.playback-endpoint}") String playbackEndpoint,
            @Value("${app.minio.region}") String region,
            @Value("${app.minio.access-key}") String accessKey,
            @Value("${app.minio.secret-key}") String secretKey,
            @Value("${app.analysis.max-source-size}") String sourceSize
    ) {
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(20))
                .writeTimeout(Duration.ofSeconds(20))
                .callTimeout(Duration.ofSeconds(75))
                .build();
        this.internal = minio(endpoint, region, accessKey, secretKey, http);
        this.preview = minio(playbackEndpoint, region, accessKey, secretKey, http);
        this.maxSourceBytes = DataSize.parse(sourceSize).toBytes();
    }

    public void downloadSource(String bucket, String key, long bytes, Path file) throws IOException {
        if (bytes <= 0 || bytes > maxSourceBytes) {
            throw new SourceSizeInvalid();
        }
        try (InputStream input = internal.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
             OutputStream output = Files.newOutputStream(file)) {
            // 以数据库已登记的长度作为上限，异常对象不能无限占用临时磁盘。
            byte[] buffer = new byte[64 * 1024];
            long remaining = bytes;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > remaining) {
                    throw new SourceSizeInvalid();
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
            if (remaining != 0) {
                throw new SourceSizeInvalid();
            }
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("读取源视频失败", exception);
        }
    }

    public void put(String bucket, String key, String contentType, Path file, long bytes) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            put(bucket, key, contentType, input, bytes);
        }
    }

    public void put(String bucket, String key, String contentType, InputStream input, long bytes) throws IOException {
        try {
            internal.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .contentType(contentType).stream(input, bytes, -1).build());
        } catch (Exception exception) {
            throw new IOException("保存素材失败", exception);
        }
    }

    public InputStream open(String bucket, String key) throws IOException {
        try {
            return internal.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception exception) {
            throw new IOException("读取对象失败", exception);
        }
    }

    public void remove(String bucket, String key, long taskId) {
        try {
            internal.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception exception) {
            LOG.warn("清理未绑定的素材失败 taskId={} exceptionType={}",
                    taskId, exception.getClass().getSimpleName());
        }
    }

    public String sign(String bucket, String key, String assetId) {
        try {
            return preview.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET)
                    .bucket(bucket).object(key).expiry(PREVIEW_TTL_SECONDS).build());
        } catch (Exception exception) {
            LOG.warn("素材预览地址签发失败 assetId={} exceptionType={}",
                    assetId, exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
        }
    }

    private static MinioClient minio(
            String endpoint, String region, String access, String secret, OkHttpClient http
    ) {
        return MinioClient.builder().endpoint(endpoint).region(region).credentials(access, secret)
                .httpClient(http).build();
    }

    public static final class SourceSizeInvalid extends IOException {
    }
}
