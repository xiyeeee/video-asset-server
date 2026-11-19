package com.xiye.videoasset.ai.analysis.service;

import com.xiye.videoasset.ai.analysis.persistence.FrameTaskRepository.FrameObject;
import com.xiye.videoasset.ai.analysis.persistence.FrameTaskRepository.Work;
import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
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
class FrameStorage {
    static final String BUCKET = "video-frames";
    static final int PREVIEW_TTL_SECONDS = 600;
    private static final Logger LOG = LoggerFactory.getLogger(FrameStorage.class);
    private static final long MAX_JPEG_BYTES = 5L * 1024 * 1024;

    private final MinioClient internal;
    private final MinioClient preview;
    private final long maxSourceBytes;

    FrameStorage(
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

    void download(Work work, Path file) {
        if (work.bytes() <= 0 || work.bytes() > maxSourceBytes) {
            throw new FrameProcessingException("SOURCE_SIZE_INVALID");
        }
        try (InputStream input = internal.getObject(GetObjectArgs.builder()
                .bucket(work.bucket()).object(work.key()).build());
             OutputStream output = Files.newOutputStream(file)) {
            // 以数据库已登记的长度作为上限，异常对象不能无限占用临时磁盘。
            byte[] buffer = new byte[64 * 1024];
            long remaining = work.bytes();
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > remaining) {
                    throw new FrameProcessingException("SOURCE_SIZE_INVALID");
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
            if (remaining != 0) {
                throw new FrameProcessingException("SOURCE_SIZE_INVALID");
            }
        } catch (FrameProcessingException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new FrameProcessingException("SOURCE_READ_FAILED", exception);
        }
    }

    long put(String key, Path image) {
        try (InputStream input = Files.newInputStream(image)) {
            long bytes = Files.size(image);
            if (bytes <= 0 || bytes > MAX_JPEG_BYTES) {
                throw new FrameProcessingException("FRAME_PROCESS_FAILED");
            }
            internal.putObject(PutObjectArgs.builder().bucket(BUCKET).object(key)
                    .contentType("image/jpeg").stream(input, bytes, -1).build());
            return bytes;
        } catch (FrameProcessingException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new FrameProcessingException("FRAME_STORE_FAILED", exception);
        }
    }

    void discardUnattached(String key, long taskId) {
        try {
            internal.removeObject(RemoveObjectArgs.builder().bucket(BUCKET).object(key).build());
        } catch (Exception exception) {
            LOG.warn("清理未绑定的分镜失败 taskId={} exceptionType={}",
                    taskId, exception.getClass().getSimpleName());
        }
    }

    String sign(FrameObject object) {
        try {
            return preview.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET)
                    .bucket(object.bucket()).object(object.key()).expiry(PREVIEW_TTL_SECONDS).build());
        } catch (Exception exception) {
            LOG.warn("分镜播放地址签发失败 assetId={} exceptionType={}",
                    object.id(), exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
        }
    }

    private static MinioClient minio(
            String endpoint, String region, String access, String secret, OkHttpClient http
    ) {
        return MinioClient.builder().endpoint(endpoint).region(region).credentials(access, secret)
                .httpClient(http).build();
    }
}
