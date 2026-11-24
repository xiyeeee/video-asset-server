package com.xiye.videoasset.ai.live2d;

import com.xiye.videoasset.ai.live2d.Live2dArchive.ModelFile;
import com.xiye.videoasset.ai.live2d.Live2dHistoryMapper.FileEntry;
import com.xiye.videoasset.ai.live2d.Live2dHistoryMapper.Legacy;
import com.xiye.videoasset.ai.live2d.Live2dHistoryMapper.Published;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class Live2dLegacyReview {
    private static final Logger LOG = LoggerFactory.getLogger(Live2dLegacyReview.class);
    private final Live2dHistoryMapper mapper;
    private final Live2dMapper tasks;
    private final Live2dHistory history;
    private final Live2dArchive archive;
    private final Live2dPackages packages;
    private final TransactionTemplate transaction;
    private final Live2dHub hub;

    Live2dLegacyReview(
            Live2dHistoryMapper mapper, Live2dMapper tasks, Live2dHistory history, Live2dArchive archive,
            Live2dPackages packages, TransactionTemplate transaction, Live2dHub hub
    ) {
        this.mapper = mapper;
        this.tasks = tasks;
        this.history = history;
        this.archive = archive;
        this.packages = packages;
        this.transaction = transaction;
        this.hub = hub;
    }

    @Scheduled(fixedDelay = 3000, scheduler = "live2dScheduler")
    void check() {
        Legacy legacy = mapper.legacy();
        if (legacy == null) {
            return;
        }
        try {
            verify(legacy);
            Published version = transaction.execute(status -> history.publishLegacy(legacy));
            if (version != null) {
                LOG.info("Live2D 历史版本已补录 taskId={} projectId={} characterId={} versionId={}",
                        legacy.id(), legacy.projectId(), version.characterId(), version.id());
                hub.publish(legacy.projectId(), legacy.id());
            }
        } catch (Live2dFailure failure) {
            if (mapper.excludeLegacy(legacy.id(), failure.getMessage()) > 0) {
                LOG.warn("Live2D 历史版本未补录 taskId={} projectId={} errorCode={}",
                        legacy.id(), legacy.projectId(), failure.getMessage());
            }
        } catch (IOException | DataAccessException exception) {
            LOG.warn("Live2D 历史补录暂不可用 taskId={} projectId={} exceptionType={}",
                    legacy.id(), legacy.projectId(), exception.getClass().getSimpleName());
        }
    }

    private void verify(Legacy legacy) throws IOException {
        if (tasks.source(legacy.projectId(), legacy.sourceAssetId()) == null) {
            throw new Live2dFailure("HISTORY_SOURCE_UNAVAILABLE");
        }
        String root = Live2dAssets.root(legacy.projectId(), legacy.id());
        String key = root + "model.zip";
        if (legacy.objectKey() == null || legacy.sizeBytes() == null
                || !Live2dAssets.BUCKET.equals(legacy.bucket())
                || !key.equals(new String(legacy.objectKey(), StandardCharsets.UTF_8))) {
            throw new Live2dFailure("ARTIFACT_MISSING");
        }
        // 历史补录只依赖持久化对象。校验包内清单和已登记的浏览器资源都存在。
        packages.requireSize(legacy.bucket(), key, legacy.sizeBytes());
        Path temp = Files.createTempDirectory("video-asset-live2d-history-");
        try {
            Path zip = packages.copyStored(legacy.bucket(), key, legacy.sizeBytes(), temp.resolve("model.zip"));
            Map<String, ModelFile> contents = archive.unpack(zip, Files.createDirectory(temp.resolve("files")),
                    legacy.manifestPath());
            List<FileEntry> files = mapper.files(legacy.modelAssetId());
            if (files.size() != contents.size()) {
                throw new Live2dFailure("ARTIFACT_MISSING");
            }
            for (FileEntry file : files) {
                ModelFile entry = contents.get(file.path());
                if (entry == null || entry.size() != file.sizeBytes()) {
                    throw new Live2dFailure("ARTIFACT_INVALID");
                }
                packages.requireSize(legacy.bucket(), root + "files/" + file.path(), file.sizeBytes());
            }
        } finally {
            Live2dWorker.clean(temp, legacy.id());
        }
    }
}
