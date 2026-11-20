package com.xiye.videoasset.ai.extraction;

import com.xiye.videoasset.api.extraction.AiTaskStatus;
import com.xiye.videoasset.api.extraction.ExtractionJob;
import com.xiye.videoasset.api.extraction.ExtractionJobState;
import com.xiye.videoasset.api.extraction.ExtractionJobWriter;
import com.xiye.videoasset.api.extraction.ExtractionType;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface ExtractionTaskMapper {
    String JOB_COLUMNS = ExtractionJobWriter.JOB_COLUMNS;
    String OWNED = """
            FROM ai_extraction_job_state j JOIN project p ON p.id = j.project_id
            WHERE p.owner_user_id = #{ownerId} AND p.deleted = 0
            """;
    String FILTERS = """
            <if test="projectId != null"> AND j.project_id = #{projectId}</if>
            <if test="episodeId != null"> AND j.episode_id = #{episodeId}</if>
            <if test="status != null"> AND j.status = #{status}</if>
            """;
    String STATE_COLUMNS = "j.id, j.project_id, j.episode_id, j.created_at, j.status, j.started_at, j.finished_at ";
    String PENDING_PUBLISH = """
            WHERE id = #{id} AND dispatched_at IS NULL AND dispatch_failed_at IS NULL
            """;

    @Select("SELECT project_id, source_asset_id FROM video_episode WHERE id = #{id} AND source_asset_id IS NOT NULL")
    EpisodeSource findSource(long id);

    @Select("SELECT " + STATE_COLUMNS + OWNED + " AND j.id = #{id}")
    ExtractionJobState owned(@Param("ownerId") long ownerId, @Param("id") long id);

    @Select("<script> SELECT " + STATE_COLUMNS + OWNED + FILTERS
            + " ORDER BY j.id DESC LIMIT #{size} OFFSET #{offset} </script>")
    List<ExtractionJobState> list(@Param("ownerId") long ownerId, @Param("projectId") Long projectId,
                                  @Param("episodeId") Long episodeId, @Param("status") AiTaskStatus status,
                                  @Param("size") int size, @Param("offset") long offset);

    @Select("<script> SELECT COUNT(*) " + OWNED + FILTERS + " </script>")
    long count(@Param("ownerId") long ownerId, @Param("projectId") Long projectId,
               @Param("episodeId") Long episodeId, @Param("status") AiTaskStatus status);

    @Select("""
            SELECT s.extract_type, s.worker_task_id, s.retry_from_worker_id,
                CASE WHEN t.status = 'SUCCEEDED' THEN 'SUCCESS'
                     WHEN t.status IS NOT NULL THEN t.status
                     WHEN j.dispatch_failed_at IS NOT NULL THEN 'FAILED'
                     ELSE 'PENDING' END AS status,
                t.python_stage, IF(t.status = 'SUCCEEDED', t.python_asset_count, NULL) AS asset_count,
                CASE WHEN t.error_code IS NOT NULL THEN t.error_code
                     WHEN s.worker_task_id IS NULL AND j.dispatch_failed_at IS NOT NULL THEN 'QUEUE_UNAVAILABLE'
                END AS error_code
            FROM ai_extraction_step s JOIN ai_extraction_job j ON j.id = s.job_id
            LEFT JOIN ai_task t ON t.id = s.worker_task_id
            WHERE s.job_id = #{id} ORDER BY s.extract_type
            """)
    List<Step> steps(long id);

    @Select("SELECT " + JOB_COLUMNS + " FROM ai_extraction_job WHERE id = #{id} FOR UPDATE")
    ExtractionJob lock(long id);

    @Select("""
            SELECT id, dispatch_attempts FROM ai_extraction_job
            WHERE dispatch_after <= UTC_TIMESTAMP(6) AND dispatch_deadline > UTC_TIMESTAMP(6)
                AND dispatched_at IS NULL AND dispatch_failed_at IS NULL AND dispatch_attempts < 5
            ORDER BY dispatch_after,id LIMIT 8
            """)
    List<PublishCandidate> due();

    @Update("""
            UPDATE ai_extraction_job SET dispatch_after = UTC_TIMESTAMP(6) + INTERVAL 15 SECOND,
                dispatch_attempts = dispatch_attempts + 1
            """ + PENDING_PUBLISH + """
                AND dispatch_attempts = #{attempts} AND dispatch_after <= UTC_TIMESTAMP(6)
                AND dispatch_deadline > UTC_TIMESTAMP(6)
            """)
    int claimPublish(@Param("id") long id, @Param("attempts") int attempts);

    @Update("""
            UPDATE ai_extraction_job SET dispatch_after = UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE,
                dispatch_attempts = 0
            """ + PENDING_PUBLISH + " AND dispatch_attempts = #{attempts}")
    void confirmPublish(@Param("id") long id, @Param("attempts") int attempts);

    @Update("""
            UPDATE ai_extraction_job SET dispatch_failed_at = UTC_TIMESTAMP(6), dispatch_after = NULL
            WHERE dispatched_at IS NULL AND dispatch_failed_at IS NULL
                AND (dispatch_deadline <= UTC_TIMESTAMP(6)
                     OR (dispatch_attempts >= 5 AND dispatch_after <= UTC_TIMESTAMP(6)))
            """)
    int expirePublish();

    @Update("""
            UPDATE ai_extraction_job SET dispatched_at = UTC_TIMESTAMP(6), dispatch_after = NULL
            """ + PENDING_PUBLISH)
    void markDispatched(long id);

    @Update("""
            UPDATE ai_extraction_job SET dispatched_at = NULL, dispatch_after = UTC_TIMESTAMP(6),
                dispatch_deadline = UTC_TIMESTAMP(6) + INTERVAL 30 MINUTE,
                dispatch_attempts = 0, dispatch_failed_at = NULL WHERE id = #{id}
            """)
    void requeue(long id);

    @Update("""
            UPDATE ai_extraction_step SET retry_from_worker_id = worker_task_id, worker_task_id = NULL
            WHERE job_id = #{id} AND extract_type = #{type}
            """)
    void resetStep(@Param("id") long id, @Param("type") ExtractionType type);

    @Update("""
            UPDATE ai_extraction_step SET worker_task_id = #{workerId}, retry_from_worker_id = NULL
            WHERE job_id = #{id} AND extract_type = #{type} AND worker_task_id IS NULL
            """)
    int bind(@Param("id") long id, @Param("type") ExtractionType type, @Param("workerId") long workerId);

    @Select("""
            SELECT id FROM ai_task
            WHERE episode_id = #{episodeId} AND source_asset_id = #{sourceId} AND task_type = #{workerType}
                AND status IN ('SUCCEEDED','PENDING','RUNNING')
            ORDER BY (status = 'SUCCEEDED') DESC, id DESC LIMIT 1
            """)
    Long reusableWorker(@Param("episodeId") long episodeId, @Param("sourceId") long sourceId,
                        @Param("workerType") String workerType);

    @Select("""
            SELECT id FROM ai_task
            WHERE episode_id = #{episodeId} AND source_asset_id = #{sourceId} AND task_type = #{workerType}
                AND status = 'FAILED' ORDER BY id DESC LIMIT 1
            """)
    Long latestFailedWorker(@Param("episodeId") long episodeId, @Param("sourceId") long sourceId,
                            @Param("workerType") String workerType);

    @Insert("""
            INSERT INTO ai_task (episode_id, task_type, idempotency_key, status, source_asset_id,
                                 python_interval_seconds, dispatch_after, run_deadline)
            VALUES (#{episodeId}, #{workerType}, #{key}, 'PENDING', #{sourceId}, #{interval},
                    UTC_TIMESTAMP(6), TIMESTAMPADD(SECOND, #{deadline}, UTC_TIMESTAMP(6)))
            ON DUPLICATE KEY UPDATE id = id
            """)
    void createWorker(@Param("episodeId") long episodeId, @Param("workerType") String workerType,
                      @Param("key") String key, @Param("sourceId") long sourceId,
                      @Param("interval") double interval, @Param("deadline") long deadline);

    @Select("""
            SELECT id FROM ai_task WHERE episode_id = #{episodeId} AND task_type = #{workerType}
                AND idempotency_key = #{key} AND source_asset_id = #{sourceId} FOR UPDATE
            """)
    Long worker(@Param("episodeId") long episodeId, @Param("workerType") String workerType,
                @Param("key") String key, @Param("sourceId") long sourceId);

    record EpisodeSource(long projectId, long sourceAssetId) {
    }

    record PublishCandidate(long id, int attempts) {
    }

    record Step(
            ExtractionType type, Long workerTaskId, Long retryFromWorkerId, AiTaskStatus status, String stage,
            Integer assetCount, String errorCode
    ) {
    }
}
