package com.xiye.videoasset.ai.characters.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskEntity;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper.DialogueSegment;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper.SourceVideo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class DialogueResultImporter {
    private static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 65_535;
    private static final int MAX_SEGMENTS = 20_000;
    private static final int INSERT_BATCH_SIZE = 100;
    private static final Logger LOG = LoggerFactory.getLogger(DialogueResultImporter.class);

    private final ObjectMapper mapper;
    private final CharacterExtractionProperties properties;
    private final CharacterTaskMapper tasks;
    private final TransactionTemplate transaction;

    DialogueResultImporter(
            ObjectMapper mapper, CharacterExtractionProperties properties,
            CharacterTaskMapper tasks, TransactionTemplate transaction
    ) {
        this.mapper = mapper;
        this.properties = properties;
        this.tasks = tasks;
        this.transaction = transaction;
    }

    boolean importResult(CharacterTaskEntity task, long lease, SourceVideo source) throws IOException {
        long started = System.nanoTime();
        try {
            List<DialogueSegment> segments = read(task);
            Boolean committed = transaction.execute(status -> {
                // 先锁工作记录。所有句段和成功状态一起提交，重复导入及零句都走同一事务。
                if (tasks.succeed(task.id(), lease) == 0) {
                    return false;
                }
                for (int start = 0; start < segments.size(); start += INSERT_BATCH_SIZE) {
                    int end = Math.min(start + INSERT_BATCH_SIZE, segments.size());
                    tasks.insertDialogue(source.projectId(), task.episodeId(), task.id(), segments.subList(start, end));
                }
                return true;
            });
            if (Boolean.TRUE.equals(committed)) {
                LOG.info("对白导入完成 taskId={} projectId={} episodeId={} durationMs={} segmentCount={}",
                        task.id(), source.projectId(), task.episodeId(), elapsed(started), segments.size());
                return true;
            }
            return false;
        } catch (IOException | RuntimeException exception) {
            String code = exception instanceof CharacterFailure failure
                    ? failure.code() : "DIALOGUE_IMPORT_UNAVAILABLE";
            LOG.warn("对白导入失败 taskId={} episodeId={} durationMs={} errorCode={}",
                    task.id(), task.episodeId(), elapsed(started), code);
            throw exception;
        }
    }

    private List<DialogueSegment> read(CharacterTaskEntity task) throws IOException {
        String ref = task.manifestRef();
        require(ref.matches(task.id() + "-dialogue-[a-zA-Z0-9_-]+/dialogue\\.json"));
        Path root = properties.outputRoot().toRealPath();
        Path expected = root.resolve(ref);
        try {
            Path manifest = expected.toRealPath();
            // 每次执行只读取自己发布的文本清单，拒绝经符号链接跨到其他执行。
            require(manifest.equals(expected) && Files.isRegularFile(manifest));
            try (InputStream input = Files.newInputStream(manifest)) {
                byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
                require(bytes.length <= MAX_MANIFEST_BYTES);
                JsonNode document = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(bytes);
                return validate(document, task.assetCount());
            }
        } catch (NoSuchFileException | JsonProcessingException exception) {
            throw invalid();
        }
    }

    private static List<DialogueSegment> validate(JsonNode document, int count) {
        require(document != null && document.isObject() && integer(document, "schemaVersion") == 1);
        long duration = integer(document, "durationMs");
        JsonNode items = document.path("segments");
        require(duration > 0 && count >= 0 && count <= MAX_SEGMENTS && items.isArray() && items.size() == count);
        List<DialogueSegment> result = new ArrayList<>(count);
        Set<String> speakers = new HashSet<>();
        long previousStart = 0;
        long previousEnd = 0;
        for (JsonNode item : items) {
            int index = result.size() + 1;
            long start = integer(item, "startMs");
            long end = integer(item, "endMs");
            // 按起点、终点排序即可，前一句未结束时允许另一句开始。
            boolean ordered = start > previousStart || start == previousStart && end >= previousEnd;
            require(integer(item, "segmentIndex") == index && start < end && end <= duration && ordered);

            JsonNode content = item.path("text");
            require(content.isTextual());
            String text = content.textValue().strip();
            require(!text.isEmpty() && text.getBytes(StandardCharsets.UTF_8).length <= MAX_TEXT_BYTES);

            JsonNode speaker = item.path("speakerId");
            require(speaker.isNull() || speaker.isTextual());
            String speakerId = speaker.isNull() ? null : speaker.textValue();
            if (speakerId != null && !speakers.contains(speakerId)) {
                require(speakerId.equals("speaker-" + (speakers.size() + 1)));
                speakers.add(speakerId);
            }

            result.add(new DialogueSegment(index, start, end, text, speakerId));
            previousStart = start;
            previousEnd = end;
        }
        return result;
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
        return new CharacterFailure("DIALOGUE_MANIFEST_INVALID");
    }

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
