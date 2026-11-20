package com.xiye.videoasset.media.extraction;

import com.xiye.videoasset.api.extraction.ExtractionPlan;
import com.xiye.videoasset.api.extraction.ExtractionType;
import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.media.episode.storage.EpisodeObjectStorage;
import com.xiye.videoasset.media.extraction.EpisodeAssetMapper.AssetRow;
import com.xiye.videoasset.media.extraction.EpisodeAssetMapper.DialogueRow;
import com.xiye.videoasset.media.extraction.EpisodeAssetMapper.WorkerStatus;
import com.xiye.videoasset.media.project.service.ProjectService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EpisodeAssets {
    private final EpisodeAssetMapper assets;
    private final ProjectService projects;
    private final EpisodeObjectStorage storage;
    private final ExtractionPlan plan;

    EpisodeAssets(
            EpisodeAssetMapper assets, ProjectService projects, EpisodeObjectStorage storage, ExtractionPlan plan
    ) {
        this.assets = assets;
        this.projects = projects;
        this.storage = storage;
        this.plan = plan;
    }

    @Transactional(readOnly = true)
    public Summary summary(String subject, long episodeId) {
        requireOwned(subject, episodeId);
        List<SummaryItem> items = Arrays.stream(ExtractionType.values()).map(type -> summary(episodeId, type)).toList();
        return new Summary(Long.toString(episodeId), items);
    }

    @Transactional(readOnly = true)
    public Page list(String subject, long episodeId, ExtractionType type, int page, int size, Long version) {
        requireOwned(subject, episodeId);
        AssetKind kind = AssetKind.of(type);
        if (kind == null) {
            return new Page(List.of(), page, size, 0, AssetStatus.UNSUPPORTED);
        }
        Long taskId = version == null ? latestWorker(episodeId, kind) : version;
        long offset = (long) (page - 1) * size;
        List<AssetView> items;
        if (kind == AssetKind.DIALOGUE) {
            items = taskId == null ? List.of() : assets.dialogue(episodeId, taskId, size, offset)
                    .stream().map(EpisodeAssets::viewDialogue).toList();
        } else {
            items = assets.list(episodeId, kind.assetType, taskId, size, offset)
                    .stream().map(row -> view(row, type)).toList();
        }
        return new Page(items, page, size, count(episodeId, kind, taskId), status(episodeId, type, kind));
    }

    private SummaryItem summary(long episodeId, ExtractionType type) {
        AssetKind kind = AssetKind.of(type);
        long count = kind == null ? 0 : count(episodeId, kind, latestWorker(episodeId, kind));
        return new SummaryItem(type, count, status(episodeId, type, kind));
    }

    private long count(long episodeId, AssetKind kind, Long taskId) {
        if (kind == AssetKind.DIALOGUE) {
            return taskId == null ? 0 : assets.countDialogue(episodeId, taskId);
        }
        return assets.count(episodeId, kind.assetType, taskId);
    }

    private AssetStatus status(long episodeId, ExtractionType type, AssetKind kind) {
        if (!plan.supports(type) || kind == null) {
            return AssetStatus.UNSUPPORTED;
        }
        WorkerStatus state = assets.stepStatus(episodeId, type);
        if (state == null) {
            state = assets.existingStatus(episodeId, kind.workerType);
        }
        return state == null ? AssetStatus.NOT_STARTED : switch (state) {
            case SUCCEEDED -> AssetStatus.SUCCESS;
            case RUNNING -> AssetStatus.RUNNING;
            case FAILED -> AssetStatus.FAILED;
            case PENDING -> AssetStatus.PENDING;
        };
    }

    private Long latestWorker(long episodeId, AssetKind kind) {
        // 选不到当前原视频的正式分镜时，由查询回退到历史首帧。
        return assets.successfulWorker(episodeId, kind.workerType);
    }

    private void requireOwned(String subject, long episodeId) {
        Long projectId = assets.project(episodeId);
        if (projectId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        projects.requireOwnedProject(subject, projectId);
    }

    private AssetView view(AssetRow row, ExtractionType type) {
        String key = new String(row.objectKey(), StandardCharsets.UTF_8);
        String url = storage.playbackUrl(row.episodeId(), row.bucket(), key);
        Details details = switch (type) {
            case SHOT -> new Shot(row.shotIndex() == null ? null : Long.toString(row.id()), row.shotIndex(),
                    row.startMs(), row.endMs(), row.previewTimestampMs(), url);
            case CHARACTER -> new CharacterImage(null, null, row.startMs(), row.sourceFrame(),
                    row.frameCharacterNo(), url);
            case AUDIO -> new Audio("ORIGINAL_TRACK", row.startMs(), row.endMs(), url);
            case BACKGROUND -> new Background(
                    row.backgroundShotAssetId() == null ? null : row.backgroundShotAssetId().toString(),
                    row.backgroundShotIndex(), row.startMs(), row.endMs(), row.backgroundFrameMs(),
                    row.backgroundMethod(), row.backgroundPersonStatus(), row.backgroundSubtitleStatus(),
                    row.backgroundReviewStatus(), url);
            default -> throw new IllegalStateException("不存在的素材映射");
        };
        String taskId = row.aiTaskId() == null ? null : row.aiTaskId().toString();
        return new AssetView(Long.toString(row.projectId()), Long.toString(row.episodeId()), Long.toString(row.id()),
                type, row.createdAt(), taskId, row.sizeBytes(), EpisodeObjectStorage.PLAYBACK_TTL_SECONDS, details);
    }

    private static AssetView viewDialogue(DialogueRow row) {
        Dialogue details = new Dialogue(row.segmentIndex(), row.startMs(), row.endMs(), row.text(),
                row.speakerId(), null);
        return new AssetView(Long.toString(row.projectId()), Long.toString(row.episodeId()),
                Long.toString(row.id()), ExtractionType.DIALOGUE, row.createdAt(), Long.toString(row.aiTaskId()),
                row.sizeBytes(), 0, details);
    }

    enum AssetKind {
        SHOT("STORYBOARD", "SHOT_EXTRACTION"),
        CHARACTER("CHARACTER_IMAGE", "CHARACTER_EXTRACTION"),
        AUDIO("AUDIO_TRACK", "AUDIO_EXTRACTION"),
        DIALOGUE(null, "DIALOGUE_EXTRACTION"),
        BACKGROUND("BACKGROUND_IMAGE", "BACKGROUND_EXTRACTION");

        final String assetType;
        final String workerType;

        AssetKind(String assetType, String workerType) {
            this.assetType = assetType;
            this.workerType = workerType;
        }

        static AssetKind of(ExtractionType type) {
            return switch (type) {
                case SHOT -> SHOT;
                case CHARACTER -> CHARACTER;
                case AUDIO -> AUDIO;
                case DIALOGUE -> DIALOGUE;
                case BACKGROUND -> BACKGROUND;
                default -> null;
            };
        }
    }

    enum AssetStatus {
        NOT_STARTED, UNSUPPORTED, PENDING, RUNNING, SUCCESS, FAILED
    }

    record Summary(String episodeId, List<SummaryItem> summary) {
    }

    record SummaryItem(ExtractionType type, long count, AssetStatus status) {
    }

    record Page(List<AssetView> items, int pageNum, int pageSize, long total, AssetStatus status) {
    }

    record AssetView(
            String projectId, String episodeId, String assetId, ExtractionType assetType, Instant createdAt,
            String sourceTaskId, long sizeBytes, int expiresInSeconds, Details details
    ) {
        @Override
        public String toString() {
            return "AssetView[assetId=" + assetId + ", assetType=" + assetType + "]";
        }
    }

    sealed interface Details permits Shot, CharacterImage, Audio, Dialogue, Background {
    }

    record Shot(
            String shotId, Integer shotIndex, Long startMs, Long endMs, Long previewTimestampMs, String frameUrl
    ) implements Details {
    }

    record CharacterImage(
            String characterId, String shotId, Long timestampMs, String sourceFrame,
            Integer frameCharacterNumber, String imageUrl
    ) implements Details {
    }

    record Audio(String audioType, Long startMs, Long endMs, String audioUrl) implements Details {
    }

    record Dialogue(
            int segmentIndex, long startMs, long endMs, String text, String speakerId, String characterId
    ) implements Details {
    }

    record Background(
            String shotId, int shotIndex, long startMs, long endMs, long timestampMs,
            String method, String personStatus, String subtitleStatus, String reviewStatus, String imageUrl
    ) implements Details {
    }
}
