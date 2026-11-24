package com.xiye.videoasset.ai.live2d;

import com.xiye.videoasset.ai.storage.AiObjectStorage;
import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import org.springframework.stereotype.Component;

@Component
class Live2dImages {
    static final long LIMIT = 10L * 1024 * 1024;
    private final AiObjectStorage storage;
    private final Live2dSettings settings;

    Live2dImages(AiObjectStorage storage, Live2dSettings settings) {
        this.storage = storage;
        this.settings = settings;
    }

    String prepare(Live2dTask task, Live2dMapper.Image source) throws IOException {
        if (source == null || source.bytes() < 1 || source.bytes() > LIMIT) {
            throw new Live2dFailure("SOURCE_IMAGE_NOT_FOUND");
        }
        String extension = extension(source.contentType());
        if (extension == null) {
            throw new Live2dFailure("SOURCE_IMAGE_INVALID");
        }
        Path root = Files.createDirectories(settings.inputRoot()).toRealPath();
        String name = "live2d-" + task.id() + extension;
        Path destination = root.resolve(name);
        if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                && Files.size(destination) == source.bytes()) {
            return name;
        }
        // 挂载目录的共享组允许 Python 读取，临时文件完整写入后才发布引用。
        Path temp = Files.getFileStore(root).supportsFileAttributeView("posix")
                ? Files.createTempFile(root, ".live2d-", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r-----")))
                : Files.createTempFile(root, ".live2d-", ".tmp");
        try {
            storage.downloadSource(source.bucket(), new String(source.key(), StandardCharsets.UTF_8),
                    source.bytes(), temp);
            try (InputStream in = Files.newInputStream(temp)) {
                if (!source.contentType().equals(detect(in))) {
                    throw new Live2dFailure("SOURCE_IMAGE_INVALID");
                }
            }
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return name;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static void validate(Live2dMapper.Image image) {
        if (image == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (image.bytes() > LIMIT) {
            throw new BusinessException(ErrorCode.IMAGE_TOO_LARGE);
        }
        if (image.bytes() <= 0 || extension(image.contentType()) == null) {
            throw new BusinessException(ErrorCode.INVALID_IMAGE);
        }
    }

    static String extension(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> null;
        };
    }

    static String detect(InputStream input) throws IOException {
        byte[] header = input.readNBytes(12);
        if (header.length < 12) {
            return null;
        }
        if (Arrays.equals(header, 0, 8, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, 0, 8)) {
            return "image/png";
        }
        if (header[0] == (byte) 255 && header[1] == (byte) 216 && header[2] == (byte) 255) {
            return "image/jpeg";
        }
        String magic = new String(header, StandardCharsets.US_ASCII);
        return magic.startsWith("RIFF") && magic.endsWith("WEBP") ? "image/webp" : null;
    }
}
