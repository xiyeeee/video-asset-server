package com.xiye.videoasset.ai.analysis.service;

import com.xiye.videoasset.ai.analysis.persistence.FrameTaskRepository.FrameObject;
import com.xiye.videoasset.ai.analysis.persistence.FrameTaskRepository.Work;
import com.xiye.videoasset.ai.storage.AiObjectStorage;
import com.xiye.videoasset.ai.storage.AiObjectStorage.SourceSizeInvalid;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
class FrameStorage {
    static final String BUCKET = "video-frames";
    static final int PREVIEW_TTL_SECONDS = AiObjectStorage.PREVIEW_TTL_SECONDS;
    private static final long MAX_JPEG_BYTES = 5L * 1024 * 1024;
    private final AiObjectStorage storage;

    FrameStorage(AiObjectStorage storage) {
        this.storage = storage;
    }

    void download(Work work, Path file) {
        try {
            storage.downloadSource(work.bucket(), work.key(), work.bytes(), file);
        } catch (SourceSizeInvalid exception) {
            throw new FrameProcessingException("SOURCE_SIZE_INVALID", exception);
        } catch (IOException exception) {
            throw new FrameProcessingException("SOURCE_READ_FAILED", exception);
        }
    }

    long put(String key, Path image) {
        try {
            long bytes = Files.size(image);
            if (bytes <= 0 || bytes > MAX_JPEG_BYTES) {
                throw new FrameProcessingException("FRAME_PROCESS_FAILED");
            }
            storage.put(BUCKET, key, "image/jpeg", image, bytes);
            return bytes;
        } catch (IOException exception) {
            throw new FrameProcessingException("FRAME_STORE_FAILED", exception);
        }
    }

    void discardUnattached(String key, long taskId) {
        storage.remove(BUCKET, key, taskId);
    }

    String sign(FrameObject object) {
        return storage.sign(object.bucket(), object.key(), object.id());
    }
}
