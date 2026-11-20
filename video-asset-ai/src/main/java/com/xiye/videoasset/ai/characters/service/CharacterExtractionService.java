package com.xiye.videoasset.ai.characters.service;

import com.xiye.videoasset.ai.analysis.service.EpisodeAnalysisAccess;
import com.xiye.videoasset.ai.characters.model.CharacterAssetVO;
import com.xiye.videoasset.ai.characters.model.CharacterTaskVO;
import com.xiye.videoasset.ai.characters.persistence.CharacterAssetEntity;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskEntity;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper;
import com.xiye.videoasset.ai.storage.AiObjectStorage;
import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CharacterExtractionService {
    private static final Logger LOG = LoggerFactory.getLogger(CharacterExtractionService.class);

    private final EpisodeAnalysisAccess access;
    private final CharacterTaskMapper tasks;
    private final AiObjectStorage storage;
    private final CharacterExtractionProperties properties;

    CharacterExtractionService(
            EpisodeAnalysisAccess access, CharacterTaskMapper tasks, AiObjectStorage storage,
            CharacterExtractionProperties properties
    ) {
        this.access = access;
        this.tasks = tasks;
        this.storage = storage;
        this.properties = properties;
    }

    public CharacterTaskVO start(String subject, long projectId, long episodeId) {
        access.requireUploadedEpisode(subject, projectId, episodeId);
        CharacterTaskEntity current = tasks.latest(episodeId);
        if (current == null) {
            current = create(episodeId, "character:v1");
        }
        return view(current);
    }

    public CharacterTaskVO latest(String subject, long projectId, long episodeId) {
        access.requireUploadedEpisode(subject, projectId, episodeId);
        CharacterTaskEntity task = tasks.latest(episodeId);
        return task == null ? null : view(task);
    }

    public CharacterTaskVO task(String subject, long projectId, long episodeId, long taskId) {
        access.requireUploadedEpisode(subject, projectId, episodeId);
        return view(requireTask(episodeId, taskId));
    }

    public CharacterTaskVO retry(String subject, long projectId, long episodeId, long taskId) {
        access.requireUploadedEpisode(subject, projectId, episodeId);
        requireTask(episodeId, taskId);
        CharacterTaskEntity latest = tasks.latest(episodeId);
        if (latest.id() == taskId && "FAILED".equals(latest.status())) {
            // Python 对终态 ID 仍返回旧结果，用户主动重试须新建业务任务，并按失败任务去重。
            latest = create(episodeId, "character:retry:" + taskId);
        }
        return view(latest);
    }

    @Transactional(readOnly = true)
    public CharacterAssetVO.Page assets(
            String subject, long projectId, long episodeId, int page, int size
    ) {
        access.requireUploadedEpisode(subject, projectId, episodeId);
        long offset = (long) (page - 1) * size;
        List<CharacterAssetVO> items = tasks.assets(projectId, episodeId, size, offset).stream()
                .map(this::assetView)
                .toList();
        return new CharacterAssetVO.Page(items, page, size, tasks.countAssets(projectId, episodeId));
    }

    private CharacterTaskEntity create(long episodeId, String idempotencyKey) {
        tasks.create(episodeId, idempotencyKey, properties.frameIntervalSeconds(),
                properties.taskTimeout().toSeconds());
        CharacterTaskEntity task = tasks.latest(episodeId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        LOG.info("已受理人物提取请求 taskId={} episodeId={} status={}", task.id(), episodeId, task.status());
        return task;
    }

    private CharacterTaskEntity requireTask(long episodeId, long taskId) {
        CharacterTaskEntity task = tasks.find(episodeId, taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return task;
    }

    private static CharacterTaskVO view(CharacterTaskEntity task) {
        Integer assetCount = "SUCCEEDED".equals(task.status()) ? task.assetCount() : null;
        CharacterTaskVO.Progress progress = new CharacterTaskVO.Progress(task.frameCount(),
                task.detectedCharacterCount());
        return new CharacterTaskVO(Long.toString(task.id()), Long.toString(task.episodeId()), task.status(),
                task.stage(), progress, assetCount, task.errorCode(), task.createdAt(), task.finishedAt());
    }

    private CharacterAssetVO assetView(CharacterAssetEntity asset) {
        String id = Long.toString(asset.id());
        String key = new String(asset.objectKey(), StandardCharsets.UTF_8);
        String preview = storage.sign(asset.bucket(), key, id);
        return new CharacterAssetVO(id, Long.toString(asset.taskId()), asset.timestampMs(), asset.sourceFrame(),
                asset.frameCharacterNumber(), asset.sizeBytes(), preview, AiObjectStorage.PREVIEW_TTL_SECONDS);
    }
}
