package com.xiye.videoasset.ai.characters.service;

import com.xiye.videoasset.ai.characters.persistence.CharacterTaskEntity;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper;
import com.xiye.videoasset.ai.characters.persistence.CharacterTaskMapper.SourceVideo;
import com.xiye.videoasset.ai.storage.AiObjectStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class CharacterResultImporter {
    static final String BUCKET = "video-assets";
    private static final Logger LOG = LoggerFactory.getLogger(CharacterResultImporter.class);

    private final CharacterManifestReader manifests;
    private final AiObjectStorage storage;
    private final CharacterTaskMapper tasks;
    private final TransactionTemplate transaction;

    CharacterResultImporter(
            CharacterManifestReader manifests, AiObjectStorage storage,
            CharacterTaskMapper tasks, TransactionTemplate transaction
    ) {
        this.manifests = manifests;
        this.storage = storage;
        this.tasks = tasks;
        this.transaction = transaction;
    }

    boolean importResult(CharacterTaskEntity task, long lease, SourceVideo source) throws IOException {
        long start = System.nanoTime();
        try {
            return storeResult(task, lease, source, start);
        } catch (IOException | RuntimeException exception) {
            String code = exception instanceof CharacterFailure failure
                    ? failure.code() : "CHARACTER_IMPORT_UNAVAILABLE";
            LOG.warn("人物素材导入失败 taskId={} durationMs={} errorCode={}", task.id(), elapsed(start), code);
            throw exception;
        }
    }

    private boolean storeResult(CharacterTaskEntity task, long lease, SourceVideo source, long start)
            throws IOException {
        CharacterManifestReader.Manifest manifest = manifests.read(task.id(), task.manifestRef(), task.assetCount());
        List<StoredAsset> objects = new ArrayList<>();
        String prefix = "projects/" + source.projectId() + "/episodes/" + task.episodeId() + "/characters/"
                + manifest.executionName() + "/";
        for (CharacterManifestReader.Asset asset : manifest.assets()) {
            if (tasks.schedule(task.id(), lease, 120) == 0) {
                return false;
            }
            String name = "character-" + (objects.size() + 1) + ".png";
            String objectKey = prefix + name;
            // 清单在首次成功响应时持久化。导入重试重复写入同一份对象，不会增加数据库素材。
            storage.put(BUCKET, objectKey, "image/png", asset.path(), asset.bytes());
            objects.add(new StoredAsset(asset, objectKey, name));
        }

        Boolean committed = transaction.execute(status -> {
            // 条件更新先锁定任务行。任何素材插入失败都会回滚状态和本次入库的全部素材。
            if (tasks.succeed(task.id(), lease) == 0) {
                return false;
            }
            for (StoredAsset object : objects) {
                CharacterManifestReader.Asset asset = object.asset();
                tasks.insertAsset(source.projectId(), task.episodeId(),
                        object.key().getBytes(StandardCharsets.UTF_8), asset.bytes(), asset.timestampMs(),
                        task.id(), asset.sourceFrame(), asset.frameCharacterNumber(), object.name());
            }
            return true;
        });
        if (Boolean.TRUE.equals(committed)) {
            LOG.info("人物素材导入完成 taskId={} episodeId={} durationMs={} status=SUCCEEDED assetCount={}",
                    task.id(), task.episodeId(), elapsed(start), objects.size());
            return true;
        }
        return false;
    }

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private record StoredAsset(CharacterManifestReader.Asset asset, String key, String name) {
    }
}
