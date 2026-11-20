package com.xiye.videoasset.ai.characters.service;

import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper.SourceVideo;
import com.xiye.videoasset.ai.storage.AiObjectStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class CharacterSharedFiles {
    private static final Logger LOG = LoggerFactory.getLogger(CharacterSharedFiles.class);
    private final CharacterExtractionProperties properties;
    private final AiObjectStorage storage;

    CharacterSharedFiles(CharacterExtractionProperties properties, AiObjectStorage storage) {
        this.properties = properties;
        this.storage = storage;
    }

    String prepare(long taskId, SourceVideo video) throws IOException {
        Path directory = Files.createDirectories(properties.inputRoot()).toRealPath();
        String reference = inputRef(taskId);
        Path target = directory.resolve(reference);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            // 超时后用原路径、原内容重发，避免覆盖正在被 Python 读取的文件。
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != video.sizeBytes()) {
                throw new CharacterFailure("SHARED_INPUT_INVALID");
            }
            return reference;
        }
        Path temporary = createStagingFile(directory);
        try {
            String objectKey = new String(video.objectKey(), StandardCharsets.UTF_8);
            storage.downloadSource(video.bucket(), objectKey, video.sizeBytes(), temporary);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            return reference;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path createStagingFile(Path directory) throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class)) {
            // 共享目录可配置共同的运行组，使 Python 能读取源视频，其他本机用户仍无权访问文件。
            return Files.createTempFile(directory, ".character-source-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r-----")));
        }
        return Files.createTempFile(directory, ".character-source-", ".tmp");
    }

    void removeInput(long taskId) {
        try {
            Files.deleteIfExists(properties.inputRoot().resolve(inputRef(taskId)));
        } catch (IOException exception) {
            LOG.warn("人物提取共享输入清理失败 taskId={} errorCode=SHARED_INPUT_CLEANUP_FAILED", taskId);
        }
    }

    private static String inputRef(long taskId) {
        return "java-character-" + taskId + ".mp4";
    }
}
