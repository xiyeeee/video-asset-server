package com.xiye.videoasset.ai.characters.service;

import com.xiye.videoasset.ai.characters.persistence.CharacterTaskEntity;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper.SourceVideo;
import com.xiye.videoasset.ai.storage.AiObjectStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class AudioResultImporter {
    private static final long MAX_WAV_BYTES = 512L * 1024 * 1024;
    private static final String BUCKET = "video-audio";
    private static final Logger LOG = LoggerFactory.getLogger(AudioResultImporter.class);

    private final CharacterExtractionProperties properties;
    private final AiObjectStorage storage;
    private final CharacterTaskMapper tasks;
    private final TransactionTemplate transaction;

    AudioResultImporter(
            CharacterExtractionProperties properties, AiObjectStorage storage,
            CharacterTaskMapper tasks, TransactionTemplate transaction
    ) {
        this.properties = properties;
        this.storage = storage;
        this.tasks = tasks;
        this.transaction = transaction;
    }

    boolean importResult(CharacterTaskEntity task, long lease, SourceVideo source) throws IOException {
        long start = System.nanoTime();
        try {
            Path file = resolve(task);
            long bytes = Files.size(file);
            long duration = duration(file, bytes);
            String objectKey = "projects/" + source.projectId() + "/episodes/" + task.episodeId()
                    + "/audio/" + file.getParent().getFileName() + "/audio.wav";
            storage.put(BUCKET, objectKey, "audio/wav", file, bytes);
            Boolean committed = transaction.execute(status -> {
                // 同一执行结果使用固定对象键，成功状态与音轨元数据在同一事务提交。
                if (tasks.succeed(task.id(), lease) == 0) {
                    return false;
                }
                tasks.insertAudio(source.projectId(), task.episodeId(), objectKey.getBytes(StandardCharsets.UTF_8),
                        bytes, duration, task.id());
                return true;
            });
            if (Boolean.TRUE.equals(committed)) {
                LOG.info("音轨导入完成 taskId={} episodeId={} durationMs={} assetCount=1",
                        task.id(), task.episodeId(), elapsed(start));
                return true;
            }
            return false;
        } catch (IOException | RuntimeException exception) {
            String code = exception instanceof CharacterFailure failure
                    ? failure.code() : "AUDIO_IMPORT_UNAVAILABLE";
            LOG.warn("音轨导入失败 taskId={} durationMs={} errorCode={}", task.id(), elapsed(start), code);
            throw exception;
        }
    }

    private Path resolve(CharacterTaskEntity task) throws IOException {
        String reference = task.manifestRef();
        if (!reference.matches(task.id() + "-audio-[a-f0-9]{32}/audio\\.wav")) {
            throw new CharacterFailure("AUDIO_RESULT_INVALID");
        }
        Path root = properties.outputRoot().toRealPath();
        Path expected = root.resolve(reference);
        try {
            Path actual = expected.toRealPath();
            if (!actual.equals(expected) || !Files.isRegularFile(actual)) {
                throw new CharacterFailure("AUDIO_RESULT_INVALID");
            }
            return actual;
        } catch (NoSuchFileException exception) {
            throw new CharacterFailure("AUDIO_RESULT_INVALID");
        }
    }

    private static long duration(Path file, long bytes) throws IOException {
        if (bytes < 44 || bytes > MAX_WAV_BYTES) {
            throw new CharacterFailure("AUDIO_RESULT_INVALID");
        }
        try {
            AudioFileFormat metadata = AudioSystem.getAudioFileFormat(file.toFile());
            AudioFormat format = metadata.getFormat();
            int frames = metadata.getFrameLength();
            if (!AudioFileFormat.Type.WAVE.equals(metadata.getType())
                    || !AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                    || format.getSampleSizeInBits() != 16 || format.getFrameSize() <= 0
                    || frames <= 0 || format.getFrameRate() <= 0
                    || (long) frames * format.getFrameSize() > bytes) {
                throw new CharacterFailure("AUDIO_RESULT_INVALID");
            }
            return Math.round(frames * 1000.0 / format.getFrameRate());
        } catch (UnsupportedAudioFileException exception) {
            throw new CharacterFailure("AUDIO_RESULT_INVALID");
        }
    }

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
