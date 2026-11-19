package com.xiye.videoasset.media.episode.storage;

import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class EpisodeObjectStorage {
    private static final Logger LOG = LoggerFactory.getLogger(EpisodeObjectStorage.class);
    private static final long PART_SIZE_BYTES = 8L * 1024 * 1024;

    public static final String ORIGINAL_BUCKET = "video-original";
    public static final int PLAYBACK_TTL_SECONDS = 600;

    private final MinioClient uploadClient;
    private final MinioClient playbackClient;

    public EpisodeObjectStorage(
            @Qualifier("episodeUploadMinioClient") MinioClient uploadClient,
            @Qualifier("episodePlaybackMinioClient") MinioClient playbackClient
    ) {
        this.uploadClient = uploadClient;
        this.playbackClient = playbackClient;
    }

    public void putOriginal(long episodeId, String objectKey, MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            uploadClient.putObject(PutObjectArgs.builder()
                    .bucket(ORIGINAL_BUCKET)
                    .object(objectKey)
                    .contentType("video/mp4")
                    .stream(input, file.getSize(), PART_SIZE_BYTES)
                    .build());
        } catch (Exception exception) {
            LOG.warn("原视频写入失败 episodeId={} exceptionType={}", episodeId, exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
        }
    }

    public String playbackUrl(long episodeId, String bucket, String objectKey) {
        try {
            return playbackClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(PLAYBACK_TTL_SECONDS)
                    .build());
        } catch (Exception exception) {
            LOG.warn("原视频签发失败 episodeId={} exceptionType={}", episodeId, exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
        }
    }
}
