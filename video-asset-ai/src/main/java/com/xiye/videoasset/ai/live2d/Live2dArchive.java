package com.xiye.videoasset.ai.live2d;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;

@Component
class Live2dArchive {
    static final long LIMIT = 128L * 1024 * 1024;
    private static final long FILE_LIMIT = 48L * 1024 * 1024;
    private static final long JSON_LIMIT = 2L * 1024 * 1024;
    private final ObjectReader manifestReader;
    private final Live2dSettings settings;

    Live2dArchive(ObjectMapper mapper, Live2dSettings settings) {
        this.manifestReader = mapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.settings = settings;
    }

    Path locate(Live2dTask task) throws IOException {
        String reference = task.artifactRef();
        require(reference != null && reference.matches(task.id() + "-[A-Za-z0-9_-]+/model\\.zip"));
        try {
            Path expected = settings.outputRoot().toRealPath().resolve(reference);
            require(expected.equals(expected.toRealPath()) && Files.isRegularFile(expected));
            long bytes = Files.size(expected);
            require(bytes > 0 && bytes <= LIMIT);
            return expected;
        } catch (NoSuchFileException exception) {
            throw new Live2dFailure("ARTIFACT_MISSING");
        }
    }

    Map<String, ModelFile> unpack(Path archive, Path directory, String manifest) throws IOException {
        require(validPath(manifest) && manifest.endsWith(".model3.json"));
        Map<String, ModelFile> files = new HashMap<>();
        Set<String> normalized = new HashSet<>();
        long total = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            require(zip.size() <= 512);
            for (ZipEntry entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    require(validPath(name.substring(0, name.length() - 1)));
                    continue;
                }
                require(validPath(name) && normalized.add(name.toLowerCase(Locale.ROOT)));
                String contentType = contentType(name);
                long limit = "application/json".equals(contentType) ? JSON_LIMIT : FILE_LIMIT;
                long declared = entry.getSize();
                require(declared > 0 && declared <= limit && declared <= LIMIT - total);
                Path target = directory.resolve(name).normalize();
                require(target.startsWith(directory));
                Files.createDirectories(target.getParent());
                copyEntry(zip, entry, target, declared);
                files.put(name, new ModelFile(target, contentType, declared));
                total += declared;
            }
            validateManifest(files, manifest);
        } catch (ZipException | JsonProcessingException | FileAlreadyExistsException exception) {
            throw new Live2dFailure("ARTIFACT_INVALID");
        }
        return Map.copyOf(files);
    }

    private static void copyEntry(ZipFile zip, ZipEntry entry, Path target, long length) throws IOException {
        CRC32 crc = new CRC32();
        long count = 0;
        // 实际解压字节数和 CRC 必须符合登记值，避免仅信任 ZIP 元数据。
        try (InputStream input = zip.getInputStream(entry);
             OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                require(count <= length);
                crc.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        require(count == length && crc.getValue() == entry.getCrc());
    }

    private void validateManifest(Map<String, ModelFile> files, String path) throws IOException {
        ModelFile entry = files.get(path);
        require(entry != null);
        JsonNode model = manifestReader.readValue(Files.readAllBytes(entry.path()));
        require(model != null && model.path("Version").isIntegralNumber() && model.path("Version").intValue() == 3);
        JsonNode references = model.path("FileReferences");
        require(references.isObject());
        String folder = path.substring(0, path.lastIndexOf('/') + 1);
        ModelFile moc = requireReference(files, folder, references.path("Moc"), ".moc3");
        try (InputStream in = Files.newInputStream(moc.path())) {
            require(Arrays.equals(in.readNBytes(4), new byte[]{'M', 'O', 'C', '3'}));
        }
        JsonNode textures = references.path("Textures");
        require(textures.isArray() && !textures.isEmpty());
        for (JsonNode texture : textures) {
            ModelFile image = requireReference(files, folder, texture, ".png");
            try (InputStream in = Files.newInputStream(image.path())) {
                require("image/png".equals(Live2dImages.detect(in)));
            }
        }
        for (String name : Set.of("Physics", "Pose", "DisplayInfo", "UserData", "MotionSync", "Controller")) {
            if (references.has(name)) {
                requireReference(files, folder, references.path(name), "");
            }
        }
        if (references.has("Expressions")) {
            require(references.path("Expressions").isArray());
            for (JsonNode expression : references.path("Expressions")) {
                requireReference(files, folder, expression.path("File"), "");
            }
        }
        if (references.has("Motions")) {
            require(references.path("Motions").isObject());
            for (JsonNode motions : references.path("Motions")) {
                require(motions.isArray());
                for (JsonNode motion : motions) {
                    requireReference(files, folder, motion.path("File"), "");
                    if (motion.has("Sound")) {
                        requireReference(files, folder, motion.path("Sound"), "");
                    }
                }
            }
        }
    }

    private static ModelFile requireReference(Map<String, ModelFile> files, String folder, JsonNode node, String suffix) {
        String value = node.textValue();
        require(validPath(value) && value.toLowerCase(Locale.ROOT).endsWith(suffix));
        ModelFile file = files.get(folder + value);
        require(file != null);
        return file;
    }

    static boolean validPath(String path) {
        if (path == null || path.length() > 512) {
            return false;
        }
        for (String part : path.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..")
                    || !part.matches("[\\p{L}\\p{N}_.() -]+")) {
                return false;
            }
        }
        return true;
    }

    private static String contentType(String path) {
        String name = path.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        require(dot >= 0);
        return switch (name.substring(dot)) {
            case ".json" -> "application/json";
            case ".moc3" -> "application/octet-stream";
            case ".png" -> "image/png";
            case ".wav" -> "audio/wav";
            case ".mp3" -> "audio/mpeg";
            case ".ogg" -> "audio/ogg";
            default -> throw new Live2dFailure("ARTIFACT_INVALID");
        };
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new Live2dFailure("ARTIFACT_INVALID");
        }
    }

    record ModelFile(Path path, String contentType, long size) {
    }
}
