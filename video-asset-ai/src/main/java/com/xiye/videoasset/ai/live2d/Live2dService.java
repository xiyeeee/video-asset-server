package com.xiye.videoasset.ai.live2d;

import com.xiye.videoasset.ai.analysis.service.EpisodeAnalysisAccess;
import com.xiye.videoasset.ai.live2d.Live2dHistoryMapper.Published;
import com.xiye.videoasset.ai.live2d.Live2dTaskVO.SaveStatus;
import com.xiye.videoasset.ai.storage.AiObjectStorage;
import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

@Service
class Live2dService {
    private static final Logger LOG = LoggerFactory.getLogger(Live2dService.class);
    private static final String TICKET = "live2d:resource:";
    private static final Duration ENTRY_TTL = Duration.ofMinutes(10);
    private final Live2dMapper tasks;
    private final Live2dHistoryMapper history;
    private final EpisodeAnalysisAccess access;
    private final AiObjectStorage storage;
    private final Live2dAssets assets;
    private final Live2dSettings settings;
    private final StringRedisTemplate redis;

    Live2dService(
            Live2dMapper tasks, Live2dHistoryMapper history, EpisodeAnalysisAccess access, AiObjectStorage storage,
            Live2dAssets assets, Live2dSettings settings, StringRedisTemplate redis
    ) {
        this.tasks = tasks;
        this.history = history;
        this.access = access;
        this.storage = storage;
        this.assets = assets;
        this.settings = settings;
        this.redis = redis;
    }

    Upload upload(String subject, long project, MultipartFile file) {
        owned(subject, project);
        if (file.getSize() > Live2dImages.LIMIT) {
            throw new BusinessException(ErrorCode.IMAGE_TOO_LARGE);
        }
        try {
            String type;
            try (InputStream input = file.getInputStream()) {
                type = Live2dImages.detect(input);
            }
            if (type == null) {
                throw new BusinessException(ErrorCode.INVALID_IMAGE);
            }
            String name = "image" + Live2dImages.extension(type);
            String key = "projects/" + project + "/uploads/" + newId() + "/" + name;
            try (InputStream input = file.getInputStream()) {
                storage.put(Live2dAssets.BUCKET, key, type, input, file.getSize());
            }
            long id = assets.insert(project, Live2dAssets.SOURCE, key, name, type, file.getSize());
            return new Upload(Long.toString(id), type, file.getSize());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
        }
    }

    Live2dTaskVO start(String subject, long project, Create request) {
        owned(subject, project);
        boolean asset = request.sourceAssetId() != null;
        String name = request.modelName() == null ? "" : request.modelName().strip();
        if (asset == (request.uploadFileId() != null) || name.isBlank() || name.length() > 255
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        long source = id(asset ? request.sourceAssetId() : request.uploadFileId());
        Live2dImages.validate(tasks.image(project, source, asset ? "CHARACTER_IMAGE" : Live2dAssets.SOURCE));
        Long character = request.characterId() == null ? null : id(request.characterId());
        if (character != null && !history.hasCharacter(project, character)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        String task = newId();
        tasks.create(task, project, source, name, character, settings.taskTimeout().toSeconds());
        LOG.info("Live2D 任务已入队 taskId={} projectId={} characterId={}", task, project, character);
        return new Live2dTaskVO(task, Live2dTask.Status.QUEUED, null, null, null, SaveStatus.NOT_STARTED, null);
    }

    Live2dTaskVO query(String subject, long project, String id) {
        owned(subject, project);
        Live2dTaskVO view = view(project, id);
        if (view == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return view;
    }

    Live2dTaskVO view(long project, String id) {
        Live2dTask task = tasks.get(id, project);
        if (task == null) {
            return null;
        }
        Live2dTaskVO.Result result = null;
        Live2dTaskVO.Error error = null;
        SaveStatus saveStatus = SaveStatus.NOT_STARTED;
        String saveError = null;
        if (task.status() == Live2dTask.Status.SUCCEEDED) {
            Long model = task.modelAssetId();
            Published version = history.published(id);
            if (model != null && tasks.resource(project, model, task.manifestPath()) != null) {
                result = new Live2dTaskVO.Result(Long.toString(model), entryUrl(project, model, task.manifestPath()),
                        version == null ? null : Long.toString(version.characterId()),
                        version == null ? null : Long.toString(version.id()));
            }
            saveError = task.historyIssue();
            saveStatus = version == null ? SaveStatus.SAVING : SaveStatus.SAVED;
        } else if (task.artifactRef() != null) {
            saveStatus = SaveStatus.SAVING;
            saveError = task.errorCode();
        }
        if (saveError != null) {
            saveStatus = SaveStatus.FAILED;
        }
        if (task.errorCode() != null) {
            error = new Live2dTaskVO.Error(
                    task.errorCode(), Live2dFailure.message(task.errorCode()), task.errorStage());
        }
        return new Live2dTaskVO(id, task.status(), task.stage(), result, error, saveStatus, saveError);
    }

    Live2dTaskVO retrySave(String subject, long project, String id) {
        owned(subject, project);
        Live2dTask task = tasks.get(id, project);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (tasks.retrySave(project, id, settings.taskTimeout().toSeconds()) == 0) {
            throw new BusinessException(ErrorCode.LIVE2D_SAVE_NOT_RETRYABLE);
        }
        LOG.info("Live2D 已重新安排保存 taskId={} projectId={} characterId={}", id, project, task.characterId());
        return view(project, id);
    }

    String entryUrl(long project, long model, String manifest) {
        return fileUrl(project, model, manifest);
    }

    String fileUrl(long project, long model, String path) {
        String ticket = newId() + newId();
        redis.opsForValue().set(TICKET + ticket, binding(project, model), ENTRY_TTL);
        return "/api/projects/" + project + "/live2d/models/" + model + "/files/" + ticket + "/"
                + UriUtils.encodePath(path, StandardCharsets.UTF_8);
    }

    ServedFile resource(long project, long model, String ticket, String path) {
        if (!Live2dArchive.validPath(path)
                || !binding(project, model).equals(redis.opsForValue().get(TICKET + ticket))) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        Live2dMapper.Resource resource = tasks.resource(project, model, path);
        if (resource == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        try {
            String key = Live2dAssets.root(project, resource.taskId()) + "files/" + path;
            return new ServedFile(storage.open(Live2dAssets.BUCKET, key), resource.contentType(), resource.bytes());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
        }
    }

    void owned(String subject, long project) {
        if (!tasks.owns(project, access.requireActiveUser(subject))) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private static String binding(long project, long model) {
        return project + ":" + model;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static long id(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0 && Long.toString(id).equals(value)) {
                return id;
            }
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }

    record Create(String modelName, String sourceAssetId, String uploadFileId, String characterId) {
    }

    record Upload(String uploadFileId, String contentType, long sizeBytes) {
    }

    record ServedFile(InputStream content, String contentType, long size) {
    }
}
