package com.xiye.videoasset.media.episode.persistence;

import com.xiye.videoasset.media.episode.model.EpisodeVO;
import com.xiye.videoasset.media.episode.model.EpisodeVO.ExtractionStatus;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class EpisodeRepository {
    private static final String ORIGINAL = """
            FROM video_episode e
            JOIN asset a ON a.id = e.source_asset_id AND a.project_id = e.project_id AND a.episode_id = e.id
            WHERE e.project_id = :projectId AND a.asset_type = 'ORIGINAL_VIDEO' AND a.deleted = 0
            """;
    // 执行中和排队优先展示。后台重跑失败时，已有成功结果仍标为已解析。
    private static final String SELECT_EPISODE = """
            SELECT e.id, e.project_id, e.episode_no, e.title, e.status, e.duration_ms,
                   a.original_filename, a.content_type, a.size_bytes, a.created_at,
                   a.bucket, a.object_key, a.id AS original_asset_id,
                   CASE GREATEST(
                       COALESCE((
                           SELECT MAX(CASE t.status
                               WHEN 'RUNNING' THEN 4 WHEN 'PENDING' THEN 3
                               WHEN 'SUCCEEDED' THEN 2 WHEN 'FAILED' THEN 1 ELSE 0 END)
                           FROM ai_task t WHERE t.episode_id = e.id
                               AND (t.source_asset_id = e.source_asset_id
                                    OR (t.task_type = 'FIRST_FRAME' AND t.source_asset_id IS NULL))
                       ), 0),
                       COALESCE((
                           SELECT MAX(CASE WHEN j.dispatch_failed_at IS NULL THEN 3 ELSE 1 END)
                           FROM ai_extraction_job j JOIN ai_extraction_step s ON s.job_id = j.id
                           WHERE j.episode_id = e.id AND j.source_asset_id = e.source_asset_id
                               AND s.worker_task_id IS NULL
                       ), 0)
                   )
                       WHEN 4 THEN 'RUNNING' WHEN 3 THEN 'QUEUED'
                       WHEN 2 THEN 'SUCCEEDED' WHEN 1 THEN 'FAILED' ELSE 'NOT_STARTED'
                   END AS extraction_status
            """;
    private static final String SELECT_RESERVATION = """
            SELECT id, upload_fingerprint, source_asset_id, episode_no, upload_options
            FROM video_episode WHERE project_id = :projectId
            """;
    private static final RowMapper<Reservation> RESERVATION = (row, number) -> new Reservation(
            row.getLong("id"), row.getBytes("upload_fingerprint"), row.getObject("source_asset_id", Long.class),
            row.getLong("episode_no"), row.getString("upload_options"));
    private static final RowMapper<OriginalEpisode> EPISODE = (row, number) -> new OriginalEpisode(
            new EpisodeVO(Long.toString(row.getLong("id")), Long.toString(row.getLong("project_id")),
                    row.getLong("episode_no"), row.getString("title"), row.getString("status"),
                    row.getString("original_filename"), row.getString("content_type"), row.getLong("size_bytes"),
                    row.getObject("duration_ms", Long.class), row.getTimestamp("created_at").toInstant(),
                    ExtractionStatus.valueOf(row.getString("extraction_status"))),
            row.getString("bucket"), new String(row.getBytes("object_key"), StandardCharsets.UTF_8),
            row.getLong("original_asset_id"));

    private final JdbcClient jdbc;

    public EpisodeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean lockOwnedProject(long projectId, long ownerId) {
        return jdbc.sql("""
                SELECT id FROM project WHERE id = :projectId AND owner_user_id = :ownerId AND deleted = 0 FOR UPDATE
                """)
                .param("projectId", projectId).param("ownerId", ownerId).query(Long.class).optional().isPresent();
    }

    public Optional<Reservation> reservationByKey(long projectId, String key) {
        return jdbc.sql(SELECT_RESERVATION + " AND upload_key = :key")
                .param("projectId", projectId).param("key", key).query(RESERVATION).optional();
    }

    public Optional<Reservation> reservationForUpdate(long projectId, long episodeId) {
        return jdbc.sql(SELECT_RESERVATION + " AND id = :episodeId FOR UPDATE")
                .param("projectId", projectId).param("episodeId", episodeId).query(RESERVATION).optional();
    }

    public long nextEpisodeNumber(long projectId) {
        return jdbc.sql("SELECT COALESCE(MAX(episode_no), 0) + 1 FROM video_episode WHERE project_id = :projectId")
                .param("projectId", projectId).query(Long.class).single();
    }

    public boolean numberExists(long projectId, long episodeNumber) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM video_episode WHERE project_id = :projectId AND episode_no = :number)
                """).param("projectId", projectId).param("number", episodeNumber).query(Boolean.class).single();
    }

    public Reservation reserve(
            long projectId, long episodeNumber, String title, String key, byte[] fingerprint, String uploadOptions
    ) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO video_episode
                    (project_id, episode_no, title, status, upload_key, upload_fingerprint, upload_options)
                VALUES (:projectId, :episodeNumber, :title, 'UPLOADING', :key, :fingerprint, :uploadOptions)
                """)
                .param("projectId", projectId).param("episodeNumber", episodeNumber).param("title", title)
                .param("key", key).param("fingerprint", fingerprint).param("uploadOptions", uploadOptions)
                .update(keys, "id");
        return new Reservation(Objects.requireNonNull(keys.getKey()).longValue(),
                fingerprint, null, episodeNumber, uploadOptions);
    }

    public long addOriginal(long projectId, long episodeId, String bucket, String key, String filename, long size) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO asset (project_id, episode_id, asset_type, bucket, object_key,
                                   original_filename, content_type, size_bytes)
                VALUES (:projectId, :episodeId, 'ORIGINAL_VIDEO', :bucket, :key, :filename, 'video/mp4', :size)
                """)
                .param("projectId", projectId).param("episodeId", episodeId).param("bucket", bucket)
                .param("key", key.getBytes(StandardCharsets.UTF_8)).param("filename", filename)
                .param("size", size).update(keys, "id");
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    public void complete(long projectId, long episodeId, long assetId) {
        jdbc.sql("""
                UPDATE video_episode SET source_asset_id = :assetId, status = 'PENDING'
                WHERE project_id = :projectId AND id = :episodeId AND source_asset_id IS NULL
                """)
                .param("projectId", projectId).param("episodeId", episodeId).param("assetId", assetId).update();
    }

    public Optional<OriginalEpisode> find(long projectId, long episodeId) {
        return jdbc.sql(SELECT_EPISODE + ORIGINAL + " AND e.id = :episodeId")
                .param("projectId", projectId).param("episodeId", episodeId).query(EPISODE).optional();
    }

    public List<EpisodeVO> list(long projectId, int size, long offset) {
        return jdbc.sql(SELECT_EPISODE + ORIGINAL + " ORDER BY e.episode_no ASC LIMIT :size OFFSET :offset")
                .param("projectId", projectId).param("size", size).param("offset", offset)
                .query(EPISODE).list().stream().map(OriginalEpisode::view).toList();
    }

    public long count(long projectId) {
        return jdbc.sql("SELECT COUNT(*) " + ORIGINAL).param("projectId", projectId).query(Long.class).single();
    }

    public record Reservation(long id, byte[] fingerprint, Long assetId, long episodeNumber, String uploadOptions) {
    }

    public record OriginalEpisode(EpisodeVO view, String bucket, String key, long assetId) {
    }
}
