package com.xiye.videoasset.media.episode.persistence;

import com.xiye.videoasset.media.episode.model.EpisodeVO;
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
    private static final String SELECT_EPISODE = """
            SELECT e.id, e.project_id, e.episode_no, e.title, e.status, e.duration_ms,
                   a.original_filename, a.content_type, a.size_bytes, a.created_at,
                   a.bucket, a.object_key, a.id AS original_asset_id
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
                    row.getObject("duration_ms", Long.class), row.getTimestamp("created_at").toInstant()),
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
