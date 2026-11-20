package com.xiye.videoasset.ai.characters.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper.ShotTiming;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import org.springframework.stereotype.Component;

@Component
class JpegTimelineReader {
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SHOTS = 10_000;
    private static final long MAX_PREVIEW_BYTES = 8L * 1024 * 1024;
    private static final long MAX_TOTAL_PREVIEW_BYTES = 512L * 1024 * 1024;
    private static final long MAX_PREVIEW_PIXELS = 16_000_000;
    private final ObjectMapper mapper;
    private final CharacterExtractionProperties properties;

    JpegTimelineReader(ObjectMapper mapper, CharacterExtractionProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    List<TimelineImage> read(long taskId, String manifestRef, int expectedCount, Format format) throws IOException {
        Path root = properties.outputRoot().toRealPath();
        Path reference = relative(manifestRef, format);
        String taskDirectory = reference.getName(0).toString();
        String prefix = taskId + "-" + format.directoryLabel + "-";
        format.require(reference.getNameCount() > 1 && reference.getFileName().toString().equals(format.arrayField + ".json")
                && taskDirectory.startsWith(prefix) && taskDirectory.length() > prefix.length());
        Path execution = root.resolve(taskDirectory);
        try {
            format.require(execution.equals(execution.toRealPath()));
            Path manifest = requireFile(root.resolve(reference), execution, format);
            JsonNode document = readJson(manifest, format);
            return validate(document, manifest.getParent(), execution, expectedCount, format);
        } catch (NoSuchFileException | JsonProcessingException exception) {
            throw format.invalid();
        }
    }

    private List<TimelineImage> validate(JsonNode root, Path folder, Path execution, int count, Format format)
            throws IOException {
        format.require(root != null && root.isObject() && integer(root, "schemaVersion", format) == 1);
        long duration = integer(root, "durationMs", format);
        JsonNode items = root.path(format.arrayField);
        format.require(duration > 0 && count > 0 && count <= MAX_SHOTS && items.isArray() && items.size() == count);
        List<TimelineImage> shots = new ArrayList<>(count);
        Set<Path> previews = new HashSet<>();
        long nextStart = 0;
        long totalBytes = 0;
        for (JsonNode item : items) {
            int index = shots.size() + 1;
            long start = integer(item, "startMs", format);
            long end = integer(item, "endMs", format);
            long timestamp = integer(item, format.timestampField, format);
            boolean coversNextInterval = start == nextStart && end > start && end <= duration;
            boolean previewInShot = timestamp >= start && timestamp < end;
            format.require(integer(item, "shotIndex", format) == index && coversNextInterval && previewInShot);

            JsonNode previewRef = item.path(format.pathField);
            format.require(previewRef.isTextual());
            Path preview = requireFile(folder.resolve(relative(previewRef.textValue(), format)), execution, format);
            format.require(previews.add(preview));
            long bytes = Files.size(preview);
            format.require(bytes > 0 && bytes <= MAX_PREVIEW_BYTES && totalBytes + bytes <= MAX_TOTAL_PREVIEW_BYTES);
            validateJpeg(preview, format);
            shots.add(new TimelineImage(new ShotTiming(index, start, end, timestamp), preview, bytes, item));
            nextStart = end;
            totalBytes += bytes;
        }
        format.require(nextStart == duration);
        return List.copyOf(shots);
    }

    private JsonNode readJson(Path path, Format format) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_JSON_BYTES + 1);
            format.require(bytes.length <= MAX_JSON_BYTES);
            return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(bytes);
        }
    }

    private static void validateJpeg(Path preview, Format format) throws IOException {
        try (FileImageInputStream input = new FileImageInputStream(preview.toFile())) {
            format.require(input.length() >= 4 && input.readUnsignedShort() == 0xffd8);
            input.seek(input.length() - 2);
            format.require(input.readUnsignedShort() == 0xffd9);
            input.seek(0);
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("JPEG");
            format.require(readers.hasNext());
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                format.require(width > 0 && height > 0 && (long) width * height <= MAX_PREVIEW_PIXELS);
                format.require(reader.read(0) != null);
            } finally {
                reader.dispose();
            }
        } catch (IIOException | EOFException exception) {
            throw format.invalid();
        }
    }

    private static Path requireFile(Path path, Path execution, Format format) throws IOException {
        Path resolved = path.toRealPath();
        // 清单与缩略图都必须属于本次原子发布，不能引用同共享目录下的另一次执行。
        format.require(resolved.startsWith(execution) && Files.isRegularFile(resolved));
        return resolved;
    }

    private static Path relative(String reference, Format format) {
        format.require(reference != null && !reference.isBlank() && reference.length() <= 512
                && !reference.contains("\\") && !reference.contains(":")
                && reference.chars().noneMatch(Character::isISOControl));
        for (String part : reference.split("/", -1)) {
            format.require(!part.isEmpty() && !".".equals(part) && !"..".equals(part));
        }
        return Path.of(reference);
    }

    private static long integer(JsonNode node, String field, Format format) {
        JsonNode value = node.path(field);
        format.require(value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0);
        return value.asLong();
    }

    enum Format {
        SHOT("shots", "shots", "previewTimestampMs", "previewPath", "SHOT_MANIFEST_INVALID"),
        BACKGROUND("background", "backgrounds", "timestampMs", "imagePath", "BACKGROUND_MANIFEST_INVALID");

        private final String directoryLabel;
        private final String arrayField;
        private final String timestampField;
        private final String pathField;
        private final String errorCode;

        Format(String directoryLabel, String arrayField, String timestampField, String pathField, String errorCode) {
            this.directoryLabel = directoryLabel;
            this.arrayField = arrayField;
            this.timestampField = timestampField;
            this.pathField = pathField;
            this.errorCode = errorCode;
        }

        void require(boolean valid) {
            if (!valid) {
                throw invalid();
            }
        }

        CharacterFailure invalid() {
            return new CharacterFailure(errorCode);
        }
    }

    record TimelineImage(ShotTiming timing, Path path, long bytes, JsonNode item) {
    }
}
