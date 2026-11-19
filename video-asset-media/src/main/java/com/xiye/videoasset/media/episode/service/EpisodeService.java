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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class EpisodeService {
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
        long ownerId = projects.requireOwnedProject(subject, projectId);
        OriginalVideo video = OriginalVideo.inspect(file);
        Reservation reservation = Objects.requireNonNull(
                transaction.execute(status -> reserve(ownerId, projectId, key, video)));
        long episodeId = reservation.id();
        if (reservation.assetId() != null) {
            return new UploadResult(original(projectId, episodeId).view(), false);
        }

        // 对象键由持久化集数决定。并发重试只能写入相同文件，存储失败后沿用同一个集数和对象键。
        String objectKey = "projects/" + projectId + "/episodes/" + episodeId + "/original.mp4";
        storage.putOriginal(episodeId, objectKey, file);
        return Objects.requireNonNull(transaction.execute(status ->
                complete(ownerId, projectId, episodeId, objectKey, video.filename(), file.getSize())));
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

    private Reservation reserve(long ownerId, long projectId, String key, OriginalVideo video) {
        lockProject(ownerId, projectId);
        // 按项目串行分配集号，避免并发上传计算出相同的下一个集号。
        return episodes.reservationByKey(projectId, key).map(existing -> {
            if (!MessageDigest.isEqual(existing.fingerprint(), video.fingerprint())) {
                throw new BusinessException(ErrorCode.VIDEO_UPLOAD_CONFLICT);
            }
            return existing;
        }).orElseGet(() -> episodes.reserve(projectId, episodes.nextEpisodeNumber(projectId),
                video.title(), key, video.fingerprint()));
    }

    private UploadResult complete(
            long ownerId, long projectId, long episodeId, String key, String filename, long size
    ) {
        // 先取项目锁，再读取本次事务的快照，确保能看到并发请求刚完成的素材绑定。
        lockProject(ownerId, projectId);
        Reservation locked = episodes.reservationForUpdate(projectId, episodeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (locked.assetId() != null) {
            return new UploadResult(original(projectId, episodeId).view(), false);
        }

        // 素材元数据与原视频引用同时提交，列表只展示已完成绑定的集数。
        long assetId = episodes.addOriginal(projectId, episodeId, EpisodeObjectStorage.ORIGINAL_BUCKET,
                key, filename, size);
        episodes.complete(projectId, episodeId, assetId);
        return new UploadResult(original(projectId, episodeId).view(), true);
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

    public record UploadResult(EpisodeVO episode, boolean created) {
    }

    public record Playback(String url, int expiresInSeconds) {
        @Override
        public String toString() {
            return "Playback[expiresInSeconds=" + expiresInSeconds + "]";
        }
    }
}
