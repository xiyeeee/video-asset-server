package com.xiye.videoasset.ai.characters.service;

import com.xiye.videoasset.ai.characters.persistence.CharacterTaskEntity;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper.SourceVideo;
import com.xiye.videoasset.ai.characters.service.JpegTimelineReader.Format;
import com.xiye.videoasset.ai.characters.service.JpegTimelineReader.TimelineImage;
import com.xiye.videoasset.ai.storage.AiObjectStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class ShotResultImporter {
    private static final String BUCKET = "video-frames";
    private static final Logger LOG = LoggerFactory.getLogger(ShotResultImporter.class);
    private final JpegTimelineReader reader;
    private final AiObjectStorage storage;
    private final CharacterTaskMapper tasks;
    private final TransactionTemplate transaction;

    ShotResultImporter(
            JpegTimelineReader reader, AiObjectStorage storage,
            CharacterTaskMapper tasks, TransactionTemplate transaction
    ) {
        this.reader = reader;
        this.storage = storage;
        this.tasks = tasks;
        this.transaction = transaction;
    }

    boolean importResult(CharacterTaskEntity task, long lease, SourceVideo source) throws IOException {
        long start = System.nanoTime();
        try {
            return publish(task, lease, source, start);
        } catch (IOException | RuntimeException exception) {
            String code = exception instanceof CharacterFailure failure ? failure.code() : "SHOT_IMPORT_UNAVAILABLE";
            LOG.warn("分镜导入失败 taskId={} episodeId={} durationMs={} errorCode={}",
                    task.id(), task.episodeId(), elapsed(start), code);
            throw exception;
        }
    }

    private boolean publish(CharacterTaskEntity task, long lease, SourceVideo source, long start) throws IOException {
        // 先校验整份清单，任何坏图、缺项或非法路径都不会发布部分分镜。
        List<TimelineImage> previews = reader.read(task.id(), task.manifestRef(), task.assetCount(), Format.SHOT);
        String prefix = "projects/" + source.projectId() + "/episodes/" + task.episodeId()
                + "/shots/" + task.id() + "/";
        List<UploadedShot> uploaded = new ArrayList<>(previews.size());
        for (TimelineImage preview : previews) {
            if (tasks.schedule(task.id(), lease, 120) == 0) {
                return false;
            }
            String name = String.format(Locale.ROOT, "shot-%06d.jpg", preview.timing().index());
            String key = prefix + name;
            // 结果引用已持久化，同一执行重放上传到原对象键。
            storage.put(BUCKET, key, "image/jpeg", preview.path(), preview.bytes());
            uploaded.add(new UploadedShot(preview, key, name));
        }

        Boolean committed = transaction.execute(status -> {
            // 任务行的条件更新和全套分镜同时提交，读接口只能看到已成功的版本。
            if (tasks.succeed(task.id(), lease) == 0) {
                return false;
            }
            for (UploadedShot object : uploaded) {
                TimelineImage preview = object.preview();
                tasks.insertShot(source.projectId(), task.episodeId(), object.key().getBytes(StandardCharsets.UTF_8),
                        preview.bytes(), task.id(), preview.timing(), object.name());
            }
            return true;
        });
        if (Boolean.TRUE.equals(committed)) {
            LOG.info("分镜导入完成 taskId={} projectId={} episodeId={} durationMs={} assetCount={}",
                    task.id(), source.projectId(), task.episodeId(), elapsed(start), uploaded.size());
            return true;
        }
        return false;
    }

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private record UploadedShot(TimelineImage preview, String key, String name) {
    }
}
