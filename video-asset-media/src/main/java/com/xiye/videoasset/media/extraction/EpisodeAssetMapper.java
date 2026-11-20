package com.xiye.videoasset.media.extraction;

import com.xiye.videoasset.api.extraction.ExtractionType;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
interface EpisodeAssetMapper {
    @Select("""
            SELECT e.project_id FROM video_episode e JOIN asset a
                ON a.id = e.source_asset_id AND a.project_id = e.project_id AND a.episode_id = e.id
            WHERE e.id = #{id} AND a.asset_type = 'ORIGINAL_VIDEO' AND a.deleted = 0
            """)
    Long project(long id);

    @Select("""
            SELECT t.id FROM ai_task t JOIN video_episode e ON e.id = t.episode_id
            WHERE t.episode_id = #{episodeId} AND t.source_asset_id = e.source_asset_id
                AND t.task_type = #{workerType} AND t.status = 'SUCCEEDED'
            ORDER BY t.id DESC LIMIT 1
            """)
    Long successfulWorker(@Param("episodeId") long episodeId, @Param("workerType") String workerType);

    @Select("""
            SELECT COALESCE(t.status, IF(j.dispatch_failed_at IS NULL, 'PENDING', 'FAILED'))
            FROM ai_extraction_step s JOIN ai_extraction_job j ON j.id = s.job_id
            JOIN video_episode e ON e.id = j.episode_id AND e.source_asset_id = j.source_asset_id
            LEFT JOIN ai_task t ON t.id = s.worker_task_id
            WHERE j.episode_id = #{episodeId} AND s.extract_type = #{type}
            ORDER BY j.id DESC LIMIT 1
            """)
    WorkerStatus stepStatus(@Param("episodeId") long episodeId, @Param("type") ExtractionType type);

    @Select("""
            SELECT t.status FROM ai_task t JOIN video_episode e ON e.id = t.episode_id
            WHERE t.episode_id = #{episodeId} AND t.source_asset_id = e.source_asset_id AND t.task_type = #{workerType}
            ORDER BY t.id DESC LIMIT 1
            """)
    WorkerStatus existingStatus(@Param("episodeId") long episodeId, @Param("workerType") String workerType);

    String ASSETS = """
            FROM asset a WHERE a.episode_id = #{episodeId} AND a.deleted = 0 AND a.asset_type = #{assetType}
                AND (
                    (#{assetType} = 'STORYBOARD' AND a.shot_index IS NULL
                        AND (#{sourceTaskId} IS NULL OR EXISTS (
                            SELECT 1 FROM ai_task t WHERE t.id = #{sourceTaskId} AND t.result_asset_id = a.id
                                AND t.episode_id = a.episode_id AND t.task_type = 'FIRST_FRAME'
                                AND t.status = 'SUCCEEDED'
                        )))
                    OR (a.ai_task_id = #{sourceTaskId} AND EXISTS (
                        SELECT 1 FROM ai_task t WHERE t.id = a.ai_task_id
                            AND t.episode_id = a.episode_id AND t.status = 'SUCCEEDED'
                    ))
                )
            """;

    @Select("""
            SELECT a.id, a.project_id, a.episode_id, a.ai_task_id, a.created_at, a.bucket, a.object_key,
                   a.start_ms, a.end_ms, a.source_frame, a.frame_character_no, a.size_bytes,
                   a.shot_index, a.preview_timestamp_ms
            """ + ASSETS + " ORDER BY a.shot_index, a.start_ms, a.id LIMIT #{size} OFFSET #{offset}")
    List<AssetRow> list(
            @Param("episodeId") long episodeId, @Param("assetType") String assetType,
            @Param("sourceTaskId") Long sourceTaskId, @Param("size") int size, @Param("offset") long offset
    );

    @Select("SELECT COUNT(*) " + ASSETS)
    long count(@Param("episodeId") long episodeId, @Param("assetType") String assetType,
               @Param("sourceTaskId") Long sourceTaskId);

    enum WorkerStatus {
        PENDING, RUNNING, SUCCEEDED, FAILED
    }

    record AssetRow(
            long id, long projectId, long episodeId, Long aiTaskId, Instant createdAt, String bucket, byte[] objectKey,
            Long startMs, Long endMs, String sourceFrame, Integer frameCharacterNo, long sizeBytes,
            Integer shotIndex, Long previewTimestampMs
    ) {
    }
}
