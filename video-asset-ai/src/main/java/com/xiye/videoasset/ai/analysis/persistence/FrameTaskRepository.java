package com.xiye.videoasset.ai.analysis.persistence;

import com.xiye.videoasset.ai.analysis.model.FrameTaskVO;
import com.xiye.videoasset.api.ai.FrameTaskMessage;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class FrameTaskRepository {
    private static final String TYPE = " AND task_type = 'FIRST_FRAME'";
    private static final String SOURCE = """
            JOIN asset a ON a.id = e.source_asset_id AND a.project_id = e.project_id AND a.episode_id = e.id
                        AND a.asset_type = 'ORIGINAL_VIDEO' AND a.deleted = 0
            """;
    private static final RowMapper<FrameTaskVO> TASK = (row, index) -> new FrameTaskVO(
            row.getString("id"), row.getString("episode_id"), row.getString("status"), row.getLong("attempt_count"),
            row.getString("error_code"), row.getString("result_asset_id"), time(row, "created_at"),
            time(row, "started_at"), time(row, "finished_at"));
    private static final int DISPATCH_ATTEMPTS = 5;
    private final JdbcClient jdbc;

    public FrameTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isActive(long ownerId) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM sys_user WHERE id = :ownerId AND deleted = 0 AND status = 'ACTIVE')
                """).param("ownerId", ownerId).query(Boolean.class).single();
    }

    public boolean ownsSource(long ownerId, long projectId, long episodeId) {
        return jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM video_episode e
                    JOIN project p ON e.project_id = p.id
                """ + SOURCE + """
                    WHERE e.id = :episodeId AND p.id = :projectId AND p.owner_user_id = :ownerId AND p.deleted = 0
                )
                """).param("ownerId", ownerId).param("projectId", projectId).param("episodeId", episodeId)
                .query(Boolean.class).single();
    }

    public Optional<FrameTaskVO> find(long episodeId) {
        return jdbc.sql("""
                SELECT id, episode_id, status, attempt_count, error_code, result_asset_id,
                       created_at, started_at, finished_at
                FROM ai_task WHERE episode_id = :episodeId AND idempotency_key = 'episode:first-frame:v1'
                """ + TYPE).param("episodeId", episodeId).query(TASK).optional();
    }

    public void create(long episodeId) {
        jdbc.sql("""
                INSERT INTO ai_task (episode_id, task_type, idempotency_key, status, dispatch_after)
                VALUES (:episodeId, 'FIRST_FRAME', 'episode:first-frame:v1', 'PENDING', UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE id = id
                """).param("episodeId", episodeId).update();
    }

    public void retry(long episodeId, long taskId, long attempt) {
        jdbc.sql("""
                UPDATE ai_task
                SET status = 'PENDING', error_code = NULL, started_at = NULL, finished_at = NULL, run_deadline = NULL,
                    dispatch_attempt_count = 0, dispatch_after = UTC_TIMESTAMP(6)
                WHERE id = :taskId AND episode_id = :episodeId AND status = 'FAILED' AND attempt_count = :attempt
                """ + TYPE).param("episodeId", episodeId).param("taskId", taskId).param("attempt", attempt).update();
    }

    public List<FrameTaskMessage> due() {
        return jdbc.sql("""
                SELECT id, attempt_count FROM ai_task
                WHERE status = 'PENDING' AND dispatch_after <= UTC_TIMESTAMP(6)
                    AND dispatch_attempt_count < :maxAttempts
                """ + TYPE + " ORDER BY dispatch_after, id LIMIT 8")
                .param("maxAttempts", DISPATCH_ATTEMPTS)
                .query((row, index) -> new FrameTaskMessage(row.getLong("id"), row.getLong("attempt_count"))).list();
    }

    public boolean claimDispatch(FrameTaskMessage message) {
        return jdbc.sql("""
                UPDATE ai_task SET dispatch_after = UTC_TIMESTAMP(6) + INTERVAL 15 SECOND,
                                   dispatch_attempt_count = dispatch_attempt_count + 1
                WHERE id = :taskId AND attempt_count = :attempt AND status = 'PENDING'
                    AND dispatch_after <= UTC_TIMESTAMP(6) AND dispatch_attempt_count < :maxAttempts
                """ + TYPE).param("taskId", message.taskId()).param("attempt", message.previousAttempt())
                .param("maxAttempts", DISPATCH_ATTEMPTS).update() == 1;
    }

    public void confirmDispatch(FrameTaskMessage message) {
        // 已确认的消息可能在队列积压时长时间未被领取，不能计作投递失败。
        // 保留低频补投，覆盖消费者在领取前退出或消息进入死信队列的情况。
        jdbc.sql("""
                UPDATE ai_task SET dispatch_after = UTC_TIMESTAMP(6) + INTERVAL 10 MINUTE,
                                   dispatch_attempt_count = 0
                WHERE id = :taskId AND attempt_count = :attempt AND status = 'PENDING'
                """ + TYPE).param("taskId", message.taskId()).param("attempt", message.previousAttempt()).update();
    }

    public void expire() {
        jdbc.sql("""
                UPDATE ai_task SET status = 'FAILED', error_code = 'TASK_TIMEOUT',
                                   finished_at = UTC_TIMESTAMP(6), run_deadline = NULL
                WHERE status = 'RUNNING' AND run_deadline <= UTC_TIMESTAMP(6)
                """ + TYPE).update();
        jdbc.sql("""
                UPDATE ai_task SET status = 'FAILED', error_code = 'QUEUE_UNAVAILABLE',
                                   finished_at = UTC_TIMESTAMP(6), dispatch_after = NULL
                WHERE status = 'PENDING' AND dispatch_after <= UTC_TIMESTAMP(6)
                    AND dispatch_attempt_count >= :maxAttempts
                """ + TYPE).param("maxAttempts", DISPATCH_ATTEMPTS).update();
    }

    public boolean claimWork(FrameTaskMessage message) {
        return jdbc.sql("""
                UPDATE ai_task SET status = 'RUNNING', attempt_count = attempt_count + 1,
                                   started_at = UTC_TIMESTAMP(6), run_deadline = UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE,
                                   dispatch_after = NULL
                WHERE id = :taskId AND attempt_count = :attempt AND status = 'PENDING'
                """ + TYPE).param("taskId", message.taskId()).param("attempt", message.previousAttempt()).update() == 1;
    }

    public Optional<Work> source(long taskId) {
        return jdbc.sql("""
                SELECT t.episode_id, e.project_id, a.bucket, a.object_key, a.size_bytes
                FROM ai_task t JOIN video_episode e ON e.id = t.episode_id
                """ + SOURCE + " WHERE t.id = :taskId")
                .param("taskId", taskId)
                .query((row, index) -> new Work(row.getLong("episode_id"), row.getLong("project_id"),
                        row.getString("bucket"), key(row), row.getLong("size_bytes"))).optional();
    }

    public boolean lockRunning(long taskId, long attempt) {
        return jdbc.sql("""
                SELECT id FROM ai_task WHERE id = :taskId AND status = 'RUNNING' AND attempt_count = :attempt
                  AND run_deadline > UTC_TIMESTAMP(6) FOR UPDATE
                """).param("taskId", taskId).param("attempt", attempt).query(Long.class).optional().isPresent();
    }

    public long insertFrame(Work work, String bucket, String key, long size) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO asset (project_id, episode_id, asset_type, bucket, object_key, original_filename,
                                   content_type, size_bytes, start_ms)
                VALUES (:projectId, :episodeId, 'STORYBOARD', :bucket, :key, 'first-frame.jpg', 'image/jpeg', :size, 0)
                """).param("projectId", work.projectId()).param("episodeId", work.episodeId())
                .param("bucket", bucket).param("key", key.getBytes(StandardCharsets.UTF_8)).param("size", size)
                .update(keys, "id");
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    public void succeed(long taskId, long assetId) {
        jdbc.sql("""
                UPDATE ai_task SET status = 'SUCCEEDED', result_asset_id = :assetId,
                                   finished_at = UTC_TIMESTAMP(6), run_deadline = NULL
                WHERE id = :taskId
                """).param("taskId", taskId).param("assetId", assetId).update();
    }

    public void fail(long taskId, long attempt, String errorCode) {
        jdbc.sql("""
                UPDATE ai_task SET status = 'FAILED', finished_at = UTC_TIMESTAMP(6),
                                   error_code = :errorCode, run_deadline = NULL
                WHERE id = :taskId AND status = 'RUNNING' AND attempt_count = :attempt
                """ + TYPE).param("taskId", taskId).param("attempt", attempt).param("errorCode", errorCode).update();
    }

    public Optional<FrameObject> frame(long projectId, long episodeId) {
        return jdbc.sql("""
                SELECT a.id, a.bucket, a.object_key, a.start_ms, a.size_bytes
                FROM ai_task t
                JOIN asset a ON a.id = t.result_asset_id AND a.episode_id = t.episode_id
                WHERE t.episode_id = :episodeId AND t.task_type = 'FIRST_FRAME'
                    AND t.idempotency_key = 'episode:first-frame:v1' AND t.status = 'SUCCEEDED'
                    AND a.project_id = :projectId AND a.asset_type = 'STORYBOARD' AND a.deleted = 0
                """).param("projectId", projectId).param("episodeId", episodeId)
                .query((row, index) -> new FrameObject(row.getString("id"), row.getString("bucket"), key(row),
                        row.getLong("start_ms"), row.getLong("size_bytes"))).optional();
    }

    private static Instant time(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String key(ResultSet row) throws SQLException {
        return new String(row.getBytes("object_key"), StandardCharsets.UTF_8);
    }

    public record Work(long episodeId, long projectId, String bucket, String key, long bytes) {
    }

    public record FrameObject(String id, String bucket, String key, long timestampMs, long sizeBytes) {
    }
}
