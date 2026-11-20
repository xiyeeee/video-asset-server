package com.xiye.videoasset.media.episode.service;

import com.xiye.videoasset.common.BusinessException;
import com.xiye.videoasset.common.ErrorCode;
import com.xiye.videoasset.media.episode.model.EpisodeVO;
import com.xiye.videoasset.media.episode.persistence.EpisodeRepository;
import com.xiye.videoasset.media.episode.persistence.EpisodeRepository.OriginalEpisode;
import com.xiye.videoasset.media.episode.persistence.EpisodeRepository.Reservation;
import com.xiye.videoasset.media.episode.storage.EpisodeObjectStorage;
import com.xiye.videoasset.media.project.service.ProjectService;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class EpisodeService {
    private static final Logger LOG = LoggerFactory.getLogger(EpisodeService.class);
    private final ProjectService projects;
    private final EpisodeRepository episodes;
    private final EpisodeObjectStorage storage;
    private final TransactionTemplate transaction;

    public EpisodeService(
            ProjectService projects, EpisodeRepository episodes,
            EpisodeObjectStorage storage, TransactionTemplate transaction
    ) {
        this.projects = projects;
        this.episodes = episodes;
        this.storage = storage;
        this.transaction = transaction;
    }

    public UploadResult upload(String subject, long projectId, String key, MultipartFile file) {
        return upload(subject, projectId, key, file, null, null);
    }

    public UploadResult upload(
            String subject, long projectId, String key, MultipartFile file, Long episodeNumber, String options
    ) {
        long started = System.nanoTime();
        long ownerId = projects.requireOwnedProject(subject, projectId);
        OriginalVideo video = OriginalVideo.inspect(file);
        Reservation reservation = Objects.requireNonNull(
                transaction.execute(status -> reserve(ownerId, projectId, key, video, episodeNumber, options)));
        long episodeId = reservation.id();
        if (reservation.assetId() != null) {
            return uploaded(original(projectId, episodeId), false);
        }

        // 对象键由持久化集数决定。并发重试只能写入相同文件，存储失败后沿用同一个集数和对象键。
        String objectKey = "projects/" + projectId + "/episodes/" + episodeId + "/original.mp4";
        LOG.info("开始写入原视频 projectId={} episodeId={}", projectId, episodeId);
        storage.putOriginal(episodeId, objectKey, file);
        UploadResult result = Objects.requireNonNull(transaction.execute(status ->
                complete(ownerId, projectId, episodeId, objectKey, video.filename(), file.getSize())));
        LOG.info("原视频已保存至 MinIO 并绑定剧集 projectId={} episodeId={} durationMs={}",
                projectId, episodeId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return result;
    }

    @Transactional(readOnly = true)
    public Page list(String subject, long projectId, int page, int size) {
        projects.requireOwnedProject(subject, projectId);
        long offset = (long) (page - 1) * size;
        return new Page(episodes.list(projectId, size, offset), page, size, episodes.count(projectId));
    }

    public Playback playback(String subject, long projectId, long episodeId) {
        projects.requireOwnedProject(subject, projectId);
        OriginalEpisode episode = original(projectId, episodeId);
        return new Playback(storage.playbackUrl(episodeId, episode.bucket(), episode.key()),
                EpisodeObjectStorage.PLAYBACK_TTL_SECONDS);
    }

    private Reservation reserve(
            long ownerId, long projectId, String key, OriginalVideo video, Long episodeNumber, String options
    ) {
        lockProject(ownerId, projectId);
        // 按项目串行分配集号，避免并发上传计算出相同的下一个集号。
        return episodes.reservationByKey(projectId, key).map(existing -> {
            boolean sameNumber = episodeNumber == null || episodeNumber == existing.episodeNumber();
            if (!sameNumber || !Objects.equals(options, existing.uploadOptions())
                    || !MessageDigest.isEqual(existing.fingerprint(), video.fingerprint())) {
                throw new BusinessException(ErrorCode.VIDEO_UPLOAD_CONFLICT);
            }
            return existing;
        }).orElseGet(() -> {
            if (episodeNumber != null && episodes.numberExists(projectId, episodeNumber)) {
                throw new BusinessException(ErrorCode.EPISODE_NUMBER_CONFLICT);
            }
            long number = episodeNumber != null ? episodeNumber : episodes.nextEpisodeNumber(projectId);
            return episodes.reserve(projectId, number, video.title(), key, video.fingerprint(), options);
        });
    }

    private UploadResult complete(
            long ownerId, long projectId, long episodeId, String key, String filename, long size
    ) {
        // 先取项目锁，再读取本次事务的快照，确保能看到并发请求刚完成的素材绑定。
        lockProject(ownerId, projectId);
        Reservation locked = episodes.reservationForUpdate(projectId, episodeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (locked.assetId() != null) {
            return uploaded(original(projectId, episodeId), false);
        }

        // 素材元数据与原视频引用同时提交，列表只展示已完成绑定的集数。
        long assetId = episodes.addOriginal(projectId, episodeId, EpisodeObjectStorage.ORIGINAL_BUCKET,
                key, filename, size);
        episodes.complete(projectId, episodeId, assetId);
        return uploaded(original(projectId, episodeId), true);
    }

    private void lockProject(long ownerId, long projectId) {
        if (!episodes.lockOwnedProject(projectId, ownerId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private OriginalEpisode original(long projectId, long episodeId) {
        return episodes.find(projectId, episodeId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    public record Page(List<EpisodeVO> items, int page, int size, long total) {
    }

    private static UploadResult uploaded(OriginalEpisode original, boolean created) {
        return new UploadResult(original.view(), original.assetId(), created);
    }

    public record UploadResult(EpisodeVO episode, long videoId, boolean created) {
    }

    public record Playback(String url, int expiresInSeconds) {
        @Override
        public String toString() {
            return "Playback[expiresInSeconds=" + expiresInSeconds + "]";
        }
    }
}
