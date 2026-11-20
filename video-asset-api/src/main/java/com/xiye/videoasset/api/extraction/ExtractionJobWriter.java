package com.xiye.videoasset.api.extraction;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExtractionJobWriter {
    String JOB_COLUMNS = """
            id, project_id, episode_id, source_asset_id, type_signature, regenerate,
            dispatch_attempts, dispatched_at, dispatch_failed_at, created_at
            """;

    @Insert("""
            INSERT INTO ai_extraction_job
                (project_id, episode_id, source_asset_id, request_key, type_signature, regenerate,
                 dispatch_after, dispatch_deadline)
            VALUES (#{projectId}, #{episodeId}, #{sourceId}, #{key}, #{signature}, #{regenerate},
                    UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 30 MINUTE)
            ON DUPLICATE KEY UPDATE id = id
            """)
    void insert(@Param("projectId") long projectId, @Param("episodeId") long episodeId,
                @Param("sourceId") long sourceId, @Param("key") String key,
                @Param("signature") String signature, @Param("regenerate") boolean regenerate);

    @Select("SELECT " + JOB_COLUMNS + """
            FROM ai_extraction_job WHERE episode_id = #{episodeId} AND source_asset_id = #{sourceId}
                AND request_key = #{key}
            """)
    ExtractionJob find(@Param("episodeId") long episodeId, @Param("sourceId") long sourceId,
                       @Param("key") String key);

    @Select("""
            SELECT id, project_id, episode_id, created_at, status, started_at, finished_at
            FROM ai_extraction_job_state WHERE id = #{id}
            """)
    ExtractionJobState state(long id);

    @Insert("""
            <script>
            INSERT INTO ai_extraction_step (job_id, extract_type) VALUES
            <foreach collection="types" item="type" separator=",">(#{taskId}, #{type})</foreach>
            ON DUPLICATE KEY UPDATE job_id = job_id
            </script>
            """)
    void addSteps(@Param("taskId") long taskId, @Param("types") List<ExtractionType> types);
}
