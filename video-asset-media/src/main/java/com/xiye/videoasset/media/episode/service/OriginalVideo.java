package com.xiye.videoasset.media.episode.service;

import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.multipart.MultipartFile;

record OriginalVideo(String filename, String title, byte[] fingerprint) {
    private static final int MAX_FILENAME_LENGTH = 255;
    private static final int MAX_TITLE_LENGTH = 150;
    private static final Set<String> MP4_BRANDS = Set.of(
            "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "iso7", "iso8", "iso9",
            "mp41", "mp42", "avc1", "dash", "M4V ", "cmfc", "cmfs", "MSNV");

    static OriginalVideo inspect(MultipartFile file) {
        String filename = leafName(file.getOriginalFilename());
        if (file.isEmpty() || filename.length() <= 4 || filename.length() > MAX_FILENAME_LENGTH
                || !filename.toLowerCase(Locale.ROOT).endsWith(".mp4")
                || filename.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.INVALID_VIDEO);
        }

        String title = filename.substring(0, filename.length() - 4).strip();
        if (title.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_VIDEO);
        }
        int titleLength = Math.min(title.codePointCount(0, title.length()), MAX_TITLE_LENGTH);
        String storedTitle = title.substring(0, title.offsetByCodePoints(0, titleLength));

        MessageDigest digest = sha256();
        digest.update(filename.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        try (InputStream input = new DigestInputStream(file.getInputStream(), digest)) {
            // 检查 MP4 文件头后继续流式计算指纹，重复请求可核对同一份文件与名称。
            checkContainer(input, file.getSize());
            input.transferTo(OutputStream.nullOutputStream());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INVALID_VIDEO);
        }
        return new OriginalVideo(filename, storedTitle, digest.digest());
    }

    private static String leafName(String filename) {
        if (filename == null) {
            return "";
        }
        String normalized = filename.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1).strip();
    }

    private static void checkContainer(InputStream input, long size) throws IOException {
        byte[] header = input.readNBytes(16);
        if (header.length < 16 || !"ftyp".equals(ascii(header, 4))) {
            throw new BusinessException(ErrorCode.INVALID_VIDEO);
        }
        long boxSize = Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt());
        if (boxSize < 16 || boxSize > size || boxSize % 4 != 0) {
            throw new BusinessException(ErrorCode.INVALID_VIDEO);
        }
        byte[] brands = input.readNBytes((int) Math.min(boxSize - 16, 256));
        if (MP4_BRANDS.contains(ascii(header, 8))) {
            return;
        }
        for (int offset = 0; offset + 4 <= brands.length; offset += 4) {
            if (MP4_BRANDS.contains(ascii(brands, offset))) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.INVALID_VIDEO);
    }

    private static String ascii(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
