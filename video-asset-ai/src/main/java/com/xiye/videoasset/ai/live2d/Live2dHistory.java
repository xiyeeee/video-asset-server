package com.xiye.videoasset.ai.live2d;

import com.xiye.videoasset.ai.live2d.Live2dHistoryMapper.Legacy;
import com.xiye.videoasset.ai.live2d.Live2dHistoryMapper.Published;
import com.xiye.videoasset.ai.live2d.Live2dHistoryMapper.Version;
import com.xiye.videoasset.ai.storage.AiObjectStorage;
import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class Live2dHistory {
    private static final Logger LOG = LoggerFactory.getLogger(Live2dHistory.class);
    private final Live2dHistoryMapper history;
    private final Live2dService service;
    private final AiObjectStorage storage;
    private final TransactionTemplate transaction;

    Live2dHistory(
            Live2dHistoryMapper history, Live2dService service, AiObjectStorage storage,
            TransactionTemplate transaction
    ) {
        this.history = history;
        this.service = service;
        this.storage = storage;
        this.transaction = transaction;
    }

    // 调用方持有任务行锁，版本与任务成功在同一事务提交。
    Published publish(String task, long project, long source, String name, Long character, long model) {
        Published existing = history.published(task);
        if (existing != null) {
            return existing;
        }
        long role = character == null ? sourceRole(project, source, name) : character;
        // 自增更新持有角色行锁，并串行分配同一角色的版本号。
        if (history.advance(project, role) != 1 || history.publish(task, role, model) != 1) {
            throw new Live2dFailure("HISTORY_CHARACTER_UNAVAILABLE");
        }
        return history.published(task);
    }

    Published publishLegacy(Legacy legacy) {
        if (history.lockLegacy(legacy.id()) == null || history.published(legacy.id()) != null) {
            return null;
        }
        return publish(legacy.id(), legacy.projectId(), legacy.sourceAssetId(), legacy.modelName(),
                null, legacy.modelAssetId());
    }

    private long sourceRole(long project, long source, String name) {
        // 旧客户端没有角色 ID 时，用项目内来源素材作稳定关联，避免把不同人物按名称合并。
        history.reserveSource(project, source);
        Long character = history.sourceCharacter(project, source);
        if (character != null) {
            return character;
        }
        int boundary = name.offsetByCodePoints(0, Math.min(150, name.codePointCount(0, name.length())));
        Live2dCharacterInsert role = new Live2dCharacterInsert(project, name.substring(0, boundary));
        history.createCharacter(role);
        history.setSourceCharacter(project, source, role.id());
        return role.id();
    }

    Page<CharacterView> characters(String subject, long project, int page, int size) {
        service.owned(subject, project);
        List<CharacterView> items = history.characters(project, size, offset(page, size)).stream()
                .map(role -> new CharacterView(Long.toString(role.id()), role.name(),
                        thumbnail(project, role.sourceAssetId(), role.sourceBucket(), role.sourceKey()),
                        Long.toString(role.latestVersionId())))
                .toList();
        return new Page<>(items, page, size, history.characterCount(project));
    }

    Page<VersionView> versions(String subject, long project, long character, int page, int size) {
        service.owned(subject, project);
        if (!history.hasCharacter(project, character)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        List<VersionView> items = history.versions(project, character, size, offset(page, size)).stream()
                .map(version -> summary(project, version)).toList();
        return new Page<>(items, page, size, history.versionCount(project, character));
    }

    void renameCharacter(String subject, long project, long character, String requestedName) {
        service.owned(subject, project);
        String name = requestedName == null ? "" : requestedName.strip();
        int nameLength = name.codePointCount(0, name.length());
        if (nameLength < 1 || nameLength > 150 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        int updated = history.renameCharacter(project, character, name);
        // 数据库对“更新成相同名称”可能返回 0；再确认角色仍存在，避免把幂等修改误报为 404。
        if (updated == 0 && !history.hasCharacter(project, character)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        LOG.info("Live2D 角色已重命名 projectId={} characterId={}", project, character);
    }

    void deleteCharacter(String subject, long project, long character) {
        service.owned(subject, project);
        Boolean deleted = transaction.execute(status -> {
            if (history.lockCharacter(project, character) == null) {
                return false;
            }
            // 生成发布也会先更新角色行；行锁保证删除与最后一次版本归档按顺序完成。
            if (history.hasPendingTasks(project, character)) {
                throw new BusinessException(ErrorCode.LIVE2D_CHARACTER_BUSY);
            }
            history.releaseCharacterSources(project, character);
            return history.deleteCharacter(project, character) == 1;
        });
        if (!Boolean.TRUE.equals(deleted)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        LOG.info("Live2D 角色已软删除 projectId={} characterId={}", project, character);
    }

    Detail detail(String subject, long project, long character, long id) {
        Version version = requireVersion(subject, project, character, id);
        return new Detail(Long.toString(project), Long.toString(character), Long.toString(version.sourceAssetId()),
                version.taskId(), summary(project, version), service.entryUrl(project, id, version.manifestPath()),
                AiObjectStorage.PREVIEW_TTL_SECONDS, "/api/projects/" + project + "/live2d/characters/" + character
                        + "/versions/" + id + "/download");
    }

    Live2dService.ServedFile download(String subject, long project, long character, long id) {
        Version version = requireVersion(subject, project, character, id);
        try {
            return new Live2dService.ServedFile(
                    storage.open(version.bucket(), new String(version.objectKey(), StandardCharsets.UTF_8)),
                    "application/zip", version.sizeBytes());
        } catch (IOException exception) {
            LOG.warn("Live2D 模型包读取失败 taskId={} projectId={} characterId={} versionId={}",
                    version.taskId(), project, character, id);
            throw new BusinessException(ErrorCode.VIDEO_STORAGE_UNAVAILABLE);
        }
    }

    private Version requireVersion(String subject, long project, long character, long id) {
        service.owned(subject, project);
        Version version = history.detail(project, character, id);
        if (version == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return version;
    }

    private VersionView summary(long project, Version version) {
        return new VersionView(Long.toString(version.id()), version.versionNumber(), version.modelName(),
                version.savedAt(), "AVAILABLE",
                thumbnail(project, version.sourceAssetId(), version.sourceBucket(), version.sourceKey()));
    }

    private String thumbnail(long project, long source, String bucket, byte[] objectKey) {
        if (objectKey == null) {
            return null;
        }
        String key = new String(objectKey, StandardCharsets.UTF_8);
        try {
            return storage.sign(bucket, key, Long.toString(source));
        } catch (BusinessException exception) {
            LOG.warn("Live2D 来源图暂不可用 projectId={} sourceAssetId={}", project, source);
            return null;
        }
    }

    private static long offset(int page, int size) {
        return (long) (page - 1) * size;
    }

    record Page<T>(List<T> items, int page, int size, long total) {
    }

    record CharacterView(String characterId, String name, String avatarUrl, String latestVersionId) {
    }

    record VersionView(String versionId, long versionNumber, String modelName, Instant savedAt, String status,
                       String thumbnailUrl) {
    }

    record Detail(String projectId, String characterId, String sourceAssetId, String taskId, VersionView version,
                  String modelEntryUrl, int previewExpiresInSeconds, String downloadUrl) {
    }
}
