package com.xiye.videoasset.ai.characters.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskEntity;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper.BackgroundMetadata;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper.ShotTiming;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper.SourceShot;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper.SourceVideo;
import com.xiye.videoasset.ai.characters.service.JpegTimelineReader.Format;
import com.xiye.videoasset.ai.characters.service.JpegTimelineReader.TimelineImage;
import com.xiye.videoasset.ai.storage.AiObjectStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class BackgroundResultImporter {
    private static final String BUCKET = "video-assets";
    private static final Set<String> DETECTION_STATUSES = Set.of("DETECTED", "NOT_DETECTED", "UNKNOWN");
    private static final Logger LOG = LoggerFactory.getLogger(BackgroundResultImporter.class);

    private final JpegTimelineReader reader;
    private final CharacterTaskMapper tasks;
    private final AiObjectStorage storage;
    private final TransactionTemplate transaction;

    BackgroundResultImporter(
            JpegTimelineReader reader, CharacterTaskMapper tasks,
            AiObjectStorage storage, TransactionTemplate transaction
    ) {
        this.reader = reader;
        this.tasks = tasks;
        this.storage = storage;
        this.transaction = transaction;
    }

    boolean importResult(CharacterTaskEntity task, long lease, SourceVideo source) throws IOException {
        long started = System.nanoTime();
        try {
            List<TimelineImage> images = reader.read(task.id(), task.manifestRef(), task.assetCount(),
                    Format.BACKGROUND);
            List<BackgroundCandidate> candidates = validate(task, source.projectId(), images);
            List<UploadedCandidate> uploaded = new ArrayList<>(candidates.size());
            String prefix = "projects/" + source.projectId() + "/episodes/" + task.episodeId()
                    + "/backgrounds/" + task.id() + "/";
            for (BackgroundCandidate candidate : candidates) {
                if (tasks.schedule(task.id(), lease, 120) == 0) {
                    return false;
                }
                String name = String.format(Locale.ROOT, "shot-%06d.jpg", candidate.metadata().shotIndex());
                String key = prefix + name;
                // 同次执行的固定对象键使数据库回滚后的重新导入仍写入同一张候选图。
                storage.put(BUCKET, key, "image/jpeg", candidate.image().path(), candidate.image().bytes());
                uploaded.add(new UploadedCandidate(candidate, key, name));
            }

            Boolean committed = transaction.execute(status -> {
                // 只有整批背景素材提交后，查询端才会看到该版本为成功。
                if (tasks.succeed(task.id(), lease) == 0) {
                    return false;
                }
                for (UploadedCandidate item : uploaded) {
                    BackgroundCandidate candidate = item.candidate();
                    tasks.insertBackground(source.projectId(), task.episodeId(), task.id(),
                            item.key().getBytes(StandardCharsets.UTF_8), candidate.image().bytes(),
                            item.name(), candidate.metadata());
                }
                return true;
            });
            if (Boolean.TRUE.equals(committed)) {
                LOG.info("背景导入完成 taskId={} episodeId={} inputShotTaskId={} durationMs={} assetCount={}",
                        task.id(), task.episodeId(), task.inputShotTaskId(), elapsed(started), uploaded.size());
                return true;
            }
            return false;
        } catch (IOException | RuntimeException exception) {
            String code = exception instanceof CharacterFailure failure
                    ? failure.code() : "BACKGROUND_IMPORT_UNAVAILABLE";
            LOG.warn("背景导入失败 taskId={} episodeId={} durationMs={} errorCode={}",
                    task.id(), task.episodeId(), elapsed(started), code);
            throw exception;
        }
    }

    private List<BackgroundCandidate> validate(CharacterTaskEntity task, long projectId, List<TimelineImage> images) {
        Long shotTaskId = task.inputShotTaskId();
        List<SourceShot> sourceShots = List.of();
        if (shotTaskId != null) {
            if (tasks.selectedShot(task.episodeId(), task.sourceAssetId(), shotTaskId) == null) {
                throw new CharacterFailure("BACKGROUND_SHOT_MANIFEST_INVALID");
            }
            sourceShots = tasks.sourceShots(shotTaskId, task.episodeId(), projectId);
            Format.BACKGROUND.require(sourceShots.size() == images.size());
        }

        List<BackgroundCandidate> candidates = new ArrayList<>(images.size());
        for (TimelineImage image : images) {
            ShotTiming timing = image.timing();
            SourceShot shot = shotTaskId == null ? null : sourceShots.get(timing.index() - 1);
            Long shotId = matchShot(timing, shot);
            String method = text(image.item(), "method");
            String person = text(image.item(), "personStatus");
            String subtitle = text(image.item(), "subtitleStatus");
            String review = text(image.item(), "reviewStatus");
            Format.BACKGROUND.require("SOURCE_FRAME".equals(method)
                    && DETECTION_STATUSES.contains(person) && DETECTION_STATUSES.contains(subtitle));
            boolean clean = "NOT_DETECTED".equals(person) && "NOT_DETECTED".equals(subtitle);
            Format.BACKGROUND.require(review.equals(clean ? "CLEAN_CANDIDATE" : "NEEDS_REVIEW"));

            BackgroundMetadata metadata = new BackgroundMetadata(timing.index(), timing.startMs(), timing.endMs(),
                    timing.previewTimestampMs(), shotId, method, person, subtitle, review);
            candidates.add(new BackgroundCandidate(image, metadata));
        }
        return candidates;
    }

    private static Long matchShot(ShotTiming timing, SourceShot shot) {
        if (shot == null) {
            return null;
        }
        // 仅按首次提交时保存的分镜版本匹配，不能把相同镜号误连到更新后或其他视频的分镜。
        Format.BACKGROUND.require(shot.index() == timing.index() && shot.startMs() == timing.startMs()
                && shot.endMs() == timing.endMs());
        return shot.deleted() ? null : shot.id();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        Format.BACKGROUND.require(value.isTextual());
        return value.textValue();
    }

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private record BackgroundCandidate(TimelineImage image, BackgroundMetadata metadata) {
    }

    private record UploadedCandidate(BackgroundCandidate candidate, String key, String name) {
    }
}
