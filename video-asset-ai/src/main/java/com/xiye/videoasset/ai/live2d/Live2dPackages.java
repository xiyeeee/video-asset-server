package com.xiye.videoasset.ai.live2d;

import com.xiye.videoasset.ai.storage.AiObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class Live2dPackages {
    private final AiObjectStorage storage;

    Live2dPackages(AiObjectStorage storage) {
        this.storage = storage;
    }

    Path copyStored(String bucket, String key, long bytes, Path target) throws IOException {
        if (bytes <= 0 || bytes > Live2dArchive.LIMIT) {
            throw new Live2dFailure("ARTIFACT_INVALID");
        }
        try (InputStream input = storage.open(bucket, key);
             OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long remaining = bytes;
            int count;
            while ((count = input.read(buffer)) != -1) {
                remaining -= count;
                if (remaining < 0) {
                    throw new Live2dFailure("ARTIFACT_INVALID");
                }
                output.write(buffer, 0, count);
            }
            if (remaining != 0) {
                throw new Live2dFailure("ARTIFACT_INVALID");
            }
        }
        return target;
    }

    void requireSize(String bucket, String key, long expected) throws IOException {
        if (!Objects.equals(storage.objectSize(bucket, key), expected)) {
            throw new Live2dFailure("ARTIFACT_MISSING");
        }
    }
}
