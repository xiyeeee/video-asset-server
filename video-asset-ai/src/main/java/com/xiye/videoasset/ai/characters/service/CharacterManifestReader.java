package com.xiye.videoasset.ai.characters.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
class CharacterManifestReader {
    private static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ASSETS = 1000;
    private static final long MAX_PNG_BYTES = 16L * 1024 * 1024;
    private static final long MAX_TOTAL_PNG_BYTES = 512L * 1024 * 1024;
    private static final Pattern EXECUTION = Pattern.compile("[0-9]+-[a-f0-9]{32}");

    private final ObjectMapper mapper;
    private final CharacterExtractionProperties properties;

    CharacterManifestReader(ObjectMapper mapper, CharacterExtractionProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    Manifest read(long taskId, String manifestRef, int expectedAssets) throws IOException {
        Path root = properties.outputRoot().toRealPath();
        Path reference = relative(manifestRef);
        String executionName = reference.getName(0).toString();
        require(reference.getNameCount() == 3 && reference.getName(1).toString().equals("characters")
                && reference.getFileName().toString().equals("characters.json")
                && EXECUTION.matcher(executionName).matches() && executionName.startsWith(taskId + "-"));
        Path execution = root.resolve(executionName);
        require(realResultPath(execution).equals(execution));
        Path manifest = file(root, manifestRef, execution);
        JsonNode data = json(manifest);
        Path framesManifest = file(manifest.getParent(), text(data, "framesManifest"), execution);
        JsonNode rawFrames = json(framesManifest);

        long width = integer(rawFrames, "width");
        long height = integer(rawFrames, "height");
        require(width > 0 && height > 0);
        Map<String, Long> rawTimestamps = rawTimestamps(framesManifest.getParent(), execution, rawFrames);
        Map<String, Frame> frames = frames(manifest.getParent(), execution, data, rawTimestamps);

        JsonNode characters = data.path("characters");
        require(characters.isArray() && expectedAssets <= MAX_ASSETS && characters.size() == expectedAssets);
        List<Asset> assets = new ArrayList<>();
        Set<FrameCharacter> identifiers = new HashSet<>();
        Set<Path> images = new HashSet<>();
        long totalBytes = 0;
        for (JsonNode candidate : characters) {
            String sourceFrame = text(candidate, "sourceFrame");
            long timestampMs = integer(candidate, "timestampMs");
            long number = integer(candidate, "characterId");
            Frame frame = frames.get(sourceFrame);
            require(frame != null && frame.timestampMs() == timestampMs && number > 0 && number <= Integer.MAX_VALUE);
            require(identifiers.add(new FrameCharacter(sourceFrame, (int) number)));
            require(file(manifest.getParent(), text(candidate, "debugImage"), execution).equals(frame.debugImage()));
            checkDetection(candidate, width, height);
            Path image = file(manifest.getParent(), text(candidate, "pngPath"), execution);
            require(images.add(image) && image.getFileName().toString().endsWith(".png"));
            long bytes = pngSize(image, width, height);
            totalBytes += bytes;
            require(totalBytes <= MAX_TOTAL_PNG_BYTES);
            assets.add(new Asset(sourceFrame, timestampMs, (int) number, image, bytes));
        }
        return new Manifest(executionName, List.copyOf(assets));
    }

    private Map<String, Long> rawTimestamps(Path base, Path execution, JsonNode data) throws IOException {
        JsonNode frames = data.path("frames");
        require(frames.isArray());
        Map<String, Long> timestamps = new HashMap<>();
        for (JsonNode frame : frames) {
            String location = text(frame, "path");
            file(base, location, execution);
            JsonNode timestamp = frame.path("timestampMs");
            require(timestamp.isNumber() && Double.isFinite(timestamp.asDouble())
                    && timestamp.asDouble() >= 0 && timestamp.asDouble() < Long.MAX_VALUE);
            require(timestamps.putIfAbsent(location, Math.round(timestamp.asDouble())) == null);
        }
        return timestamps;
    }

    private Map<String, Frame> frames(Path base, Path execution, JsonNode data, Map<String, Long> rawTimestamps)
            throws IOException {
        JsonNode frames = data.path("frames");
        require(frames.isArray() && frames.size() == rawTimestamps.size());
        Map<String, Frame> indexed = new HashMap<>();
        for (JsonNode frame : frames) {
            String source = text(frame, "sourceFrame");
            long timestamp = integer(frame, "timestampMs");
            Long actualTime = rawTimestamps.get(source);
            require(source.length() <= 255 && actualTime != null && actualTime == timestamp);
            Frame metadata = new Frame(timestamp, file(base, text(frame, "debugImage"), execution));
            require(indexed.putIfAbsent(source, metadata) == null);
        }
        return indexed;
    }

    private static void checkDetection(JsonNode candidate, long width, long height) {
        JsonNode box = candidate.path("box");
        JsonNode score = candidate.path("score");
        require(box.isArray() && box.size() == 4 && score.isNumber()
                && score.asDouble() >= 0 && score.asDouble() <= 1);
        for (JsonNode coordinate : box) {
            require(coordinate.isIntegralNumber() && coordinate.canConvertToLong() && coordinate.asLong() >= 0);
        }
        require(box.get(0).asLong() < box.get(2).asLong() && box.get(2).asLong() <= width
                && box.get(1).asLong() < box.get(3).asLong() && box.get(3).asLong() <= height);
    }

    private static long pngSize(Path image, long width, long height) throws IOException {
        long bytes = Files.size(image);
        require(bytes >= 45 && bytes <= MAX_PNG_BYTES);
        try (DataInputStream input = new DataInputStream(Files.newInputStream(image))) {
            require(input.readLong() == 0x89504e470d0a1a0aL);
            require(input.readInt() == 13 && input.readInt() == 0x49484452);
            int pngWidth = input.readInt();
            int pngHeight = input.readInt();
            input.readUnsignedByte(); // 位深
            int colorType = input.readUnsignedByte();
            require(pngWidth > 0 && pngWidth <= width && pngHeight > 0 && pngHeight <= height
                    && (colorType == 4 || colorType == 6));
        }
        return bytes;
    }

    private JsonNode json(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            require(bytes.length <= MAX_MANIFEST_BYTES);
            JsonNode data = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            require(data != null && data.isObject());
            return data;
        } catch (JsonProcessingException exception) {
            throw new CharacterFailure("CHARACTER_MANIFEST_INVALID");
        }
    }

    private static Path file(Path base, String relativeRef, Path execution) throws IOException {
        Path path = base.resolve(relative(relativeRef)).normalize();
        require(path.startsWith(execution));
        // 内部引用允许 ../frames，实际目标仍必须处于同一次 Python 执行目录。
        Path actual = realResultPath(path);
        require(actual.startsWith(execution) && Files.isRegularFile(actual));
        return actual;
    }

    private static Path realResultPath(Path path) throws IOException {
        try {
            return path.toRealPath();
        } catch (NoSuchFileException exception) {
            // Python 原子发布成功后应已有全部文件，缺少内部文件属于产物不完整。
            throw new CharacterFailure("CHARACTER_MANIFEST_INVALID");
        }
    }

    private static Path relative(String value) {
        require(value != null && !value.isBlank() && value.length() <= 512
                && !value.contains("\\") && !value.contains(":") && !value.contains("//")
                && value.chars().noneMatch(Character::isISOControl));
        Path path = Path.of(value);
        require(!path.isAbsolute() && path.getNameCount() > 0);
        return path;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isTextual());
        return value.textValue();
    }

    private static long integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0);
        return value.asLong();
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new CharacterFailure("CHARACTER_MANIFEST_INVALID");
        }
    }

    private record Frame(long timestampMs, Path debugImage) {
    }

    private record FrameCharacter(String sourceFrame, int number) {
    }

    record Asset(String sourceFrame, long timestampMs, int frameCharacterNumber, Path path, long bytes) {
    }

    record Manifest(String executionName, List<Asset> assets) {
    }
}
