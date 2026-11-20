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
class ShotManifestReader {
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SHOTS = 10_000;
    private static final long MAX_PREVIEW_BYTES = 8L * 1024 * 1024;
    private static final long MAX_TOTAL_PREVIEW_BYTES = 512L * 1024 * 1024;
    private static final long MAX_PREVIEW_PIXELS = 16_000_000;
    private final ObjectMapper mapper;
    private final CharacterExtractionProperties properties;

    ShotManifestReader(ObjectMapper mapper, CharacterExtractionProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    List<ShotPreview> read(long taskId, String manifestRef, int expectedCount) throws IOException {
        Path root = properties.outputRoot().toRealPath();
        Path reference = relative(manifestRef);
        String taskDirectory = reference.getName(0).toString();
        String prefix = taskId + "-shots-";
        require(reference.getNameCount() > 1 && reference.getFileName().toString().equals("shots.json")
                && taskDirectory.startsWith(prefix) && taskDirectory.length() > prefix.length());
        Path execution = root.resolve(taskDirectory);
        try {
            require(execution.equals(execution.toRealPath()));
            Path manifest = requireFile(root.resolve(reference), execution);
            JsonNode document = readJson(manifest);
            return validate(document, manifest.getParent(), execution, expectedCount);
        } catch (NoSuchFileException | JsonProcessingException exception) {
            throw invalid();
        }
    }

    private List<ShotPreview> validate(JsonNode root, Path folder, Path execution, int count) throws IOException {
        require(root != null && root.isObject() && integer(root, "schemaVersion") == 1);
        long duration = integer(root, "durationMs");
        JsonNode items = root.path("shots");
        require(duration > 0 && count > 0 && count <= MAX_SHOTS && items.isArray() && items.size() == count);
        List<ShotPreview> shots = new ArrayList<>(count);
        Set<Path> previews = new HashSet<>();
        long nextStart = 0;
        long totalBytes = 0;
        for (JsonNode item : items) {
            int index = shots.size() + 1;
            long start = integer(item, "startMs");
            long end = integer(item, "endMs");
            long timestamp = integer(item, "previewTimestampMs");
            boolean coversNextInterval = start == nextStart && end > start && end <= duration;
            boolean previewInShot = timestamp >= start && timestamp < end;
            require(integer(item, "shotIndex") == index && coversNextInterval && previewInShot);

            JsonNode previewRef = item.path("previewPath");
            require(previewRef.isTextual());
            Path preview = requireFile(folder.resolve(relative(previewRef.textValue())), execution);
            require(previews.add(preview));
            long bytes = Files.size(preview);
            require(bytes > 0 && bytes <= MAX_PREVIEW_BYTES && totalBytes + bytes <= MAX_TOTAL_PREVIEW_BYTES);
            validateJpeg(preview);
            shots.add(new ShotPreview(new ShotTiming(index, start, end, timestamp), preview, bytes));
            nextStart = end;
            totalBytes += bytes;
        }
        require(nextStart == duration);
        return List.copyOf(shots);
    }

    private JsonNode readJson(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_JSON_BYTES + 1);
            require(bytes.length <= MAX_JSON_BYTES);
            return mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(bytes);
        }
    }

    private static void validateJpeg(Path preview) throws IOException {
        try (FileImageInputStream input = new FileImageInputStream(preview.toFile())) {
            require(input.length() >= 4 && input.readUnsignedShort() == 0xffd8);
            input.seek(input.length() - 2);
            require(input.readUnsignedShort() == 0xffd9);
            input.seek(0);
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("JPEG");
            require(readers.hasNext());
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                require(width > 0 && height > 0 && (long) width * height <= MAX_PREVIEW_PIXELS);
                require(reader.read(0) != null);
            } finally {
                reader.dispose();
            }
        } catch (IIOException | EOFException exception) {
            throw invalid();
        }
    }

    private static Path requireFile(Path path, Path execution) throws IOException {
        Path resolved = path.toRealPath();
        // 清单与缩略图都必须属于本次原子发布，不能引用同共享目录下的另一次执行。
        require(resolved.startsWith(execution) && Files.isRegularFile(resolved));
        return resolved;
    }

    private static Path relative(String reference) {
        require(reference != null && !reference.isBlank() && reference.length() <= 512
                && !reference.contains("\\") && !reference.contains(":")
                && reference.chars().noneMatch(Character::isISOControl));
        for (String part : reference.split("/", -1)) {
            require(!part.isEmpty() && !".".equals(part) && !"..".equals(part));
        }
        return Path.of(reference);
    }

    private static long integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0);
        return value.asLong();
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw invalid();
        }
    }

    private static CharacterFailure invalid() {
        return new CharacterFailure("SHOT_MANIFEST_INVALID");
    }

    record ShotPreview(ShotTiming timing, Path path, long bytes) {
    }
}
