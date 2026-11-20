package com.xiye.videoasset.ai.characters.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CharacterTaskMapper {
    String TASK_COLUMNS = """
            id, episode_id, status, attempt_count, dispatch_attempt_count AS lease_version, source_asset_id,
            python_interval_seconds AS interval_seconds, python_stage AS stage, python_frame_count AS frame_count,
            python_detected_count AS detected_character_count, python_manifest_ref AS manifest_ref,
            python_asset_count AS asset_count, error_code, created_at, finished_at, task_type
            """;
    String ACTIVE_LEASE = """
            WHERE id = #{id} AND task_type IN ('CHARACTER_EXTRACTION', 'AUDIO_EXTRACTION', 'SHOT_EXTRACTION')
                AND status IN ('PENDING', 'RUNNING') AND dispatch_attempt_count = #{lease}
                AND run_deadline > UTC_TIMESTAMP(6)
            """;

    @Select("SELECT " + TASK_COLUMNS + """
            FROM ai_task WHERE episode_id = #{episodeId} AND task_type = 'CHARACTER_EXTRACTION'
            ORDER BY id DESC LIMIT 1
            """)
    CharacterTaskEntity latest(long episodeId);

    @Select("SELECT " + TASK_COLUMNS + """
            FROM ai_task WHERE episode_id = #{episodeId} AND id = #{id} AND task_type = 'CHARACTER_EXTRACTION'
            """)
    CharacterTaskEntity find(@Param("episodeId") long episodeId, @Param("id") long id);

    @Insert("""
            INSERT INTO ai_task (episode_id, task_type, idempotency_key, status, source_asset_id,
                                 python_interval_seconds, dispatch_after, run_deadline)
            SELECT id, 'CHARACTER_EXTRACTION', #{key}, 'PENDING', source_asset_id,
                   #{interval}, UTC_TIMESTAMP(6), TIMESTAMPADD(SECOND, #{deadlineSeconds}, UTC_TIMESTAMP(6))
            FROM video_episode WHERE id = #{episodeId} AND source_asset_id IS NOT NULL
            ON DUPLICATE KEY UPDATE ai_task.id = ai_task.id
            """)
    void create(@Param("episodeId") long episodeId, @Param("key") String key,
                @Param("interval") double interval, @Param("deadlineSeconds") long deadlineSeconds);

    @Select("SELECT " + TASK_COLUMNS + """
            FROM ai_task
            WHERE task_type IN ('CHARACTER_EXTRACTION', 'AUDIO_EXTRACTION', 'SHOT_EXTRACTION')
                AND status IN ('PENDING', 'RUNNING')
                AND dispatch_after <= UTC_TIMESTAMP(6) AND run_deadline > UTC_TIMESTAMP(6)
            ORDER BY dispatch_after, id LIMIT 1
            """)
    CharacterTaskEntity due();

    @Update("""
            UPDATE ai_task SET dispatch_after = UTC_TIMESTAMP(6) + INTERVAL 2 MINUTE,
                dispatch_attempt_count = dispatch_attempt_count + 1
            """ + ACTIVE_LEASE + " AND dispatch_after <= UTC_TIMESTAMP(6)")
    int claim(@Param("id") long id, @Param("lease") long lease);

    @Update("""
            UPDATE ai_task SET status = 'FAILED',
                error_code = IF(task_type = 'CHARACTER_EXTRACTION',
                                'CHARACTER_TASK_TIMEOUT', 'EXTRACTION_TASK_TIMEOUT'),
                finished_at = UTC_TIMESTAMP(6), dispatch_after = NULL, run_deadline = NULL
            WHERE task_type IN ('CHARACTER_EXTRACTION', 'AUDIO_EXTRACTION', 'SHOT_EXTRACTION')
                AND status IN ('PENDING', 'RUNNING')
                AND run_deadline <= UTC_TIMESTAMP(6)
            """)
    int expire();

    @Update("""
            UPDATE ai_task SET attempt_count = attempt_count + 1
            """ + ACTIVE_LEASE)
    int recordSubmission(@Param("id") long id, @Param("lease") long lease);

    @Update("""
            UPDATE ai_task SET dispatch_after = TIMESTAMPADD(SECOND, #{delaySeconds}, UTC_TIMESTAMP(6))
            """ + ACTIVE_LEASE)
    int schedule(@Param("id") long id, @Param("lease") long lease, @Param("delaySeconds") long delaySeconds);

    @Update("""
            UPDATE ai_task SET status = #{status}, python_stage = #{stage}, python_frame_count = #{frameCount},
                python_detected_count = #{detectedCount},
                started_at = IF(#{status} = 'RUNNING', COALESCE(started_at, UTC_TIMESTAMP(6)), started_at),
                python_manifest_ref = #{manifest}, python_asset_count = #{assetCount}
            """ + ACTIVE_LEASE)
    int progress(@Param("id") long id, @Param("lease") long lease, @Param("status") String status,
                 @Param("stage") String stage, @Param("frameCount") Integer frameCount,
                 @Param("detectedCount") Integer detectedCount, @Param("manifest") String manifest,
                 @Param("assetCount") Integer assetCount);

    @Update("""
            UPDATE ai_task SET status = 'FAILED', error_code = #{errorCode}, finished_at = UTC_TIMESTAMP(6),
                dispatch_after = NULL, run_deadline = NULL
            """ + ACTIVE_LEASE)
    int fail(@Param("id") long id, @Param("lease") long lease, @Param("errorCode") String errorCode);

    @Update("""
            UPDATE ai_task SET status = 'FAILED', error_code = #{errorCode}, python_stage = #{stage},
                python_frame_count = #{frameCount}, python_detected_count = #{detectedCount},
                finished_at = UTC_TIMESTAMP(6), dispatch_after = NULL, run_deadline = NULL
            """ + ACTIVE_LEASE)
    int failFromPython(@Param("id") long id, @Param("lease") long lease, @Param("errorCode") String errorCode,
                       @Param("stage") String stage, @Param("frameCount") Integer frameCount,
                       @Param("detectedCount") Integer detectedCount);

    @Update("""
            UPDATE ai_task SET status = 'SUCCEEDED', finished_at = UTC_TIMESTAMP(6),
                dispatch_after = NULL, run_deadline = NULL
            """ + ACTIVE_LEASE + " AND python_manifest_ref IS NOT NULL")
    int succeed(@Param("id") long id, @Param("lease") long lease);

    @Select("""
            SELECT e.project_id, a.bucket, a.object_key, a.size_bytes
            FROM video_episode e JOIN asset a ON a.id = e.source_asset_id AND a.episode_id = e.id
                AND a.project_id = e.project_id AND a.asset_type = 'ORIGINAL_VIDEO' AND a.deleted = 0
            WHERE e.id = #{episodeId} AND a.id = #{assetId}
            """)
    SourceVideo source(@Param("episodeId") long episodeId, @Param("assetId") long assetId);

    @Insert("""
            INSERT INTO asset (project_id, episode_id, asset_type, bucket, object_key, content_type, size_bytes,
                               start_ms, ai_task_id, source_frame, frame_character_no, original_filename)
            VALUES (#{projectId}, #{episodeId}, 'CHARACTER_IMAGE', 'video-assets', #{objectKey}, 'image/png', #{bytes},
                    #{timestampMs}, #{taskId}, #{sourceFrame}, #{frameCharacterNumber}, #{name})
            """)
    void insertAsset(@Param("projectId") long projectId, @Param("episodeId") long episodeId,
                     @Param("objectKey") byte[] objectKey, @Param("bytes") long bytes,
                     @Param("timestampMs") long timestampMs, @Param("taskId") long taskId,
                     @Param("sourceFrame") String sourceFrame, @Param("frameCharacterNumber") int frameCharacterNumber,
                     @Param("name") String name);

    @Insert("""
            INSERT INTO asset (project_id, episode_id, asset_type, bucket, object_key, content_type, size_bytes,
                               start_ms, end_ms, ai_task_id, original_filename)
            VALUES (#{projectId}, #{episodeId}, 'AUDIO_TRACK', 'video-audio', #{objectKey}, 'audio/wav',
                    #{bytes}, 0, #{durationMs}, #{taskId}, 'audio.wav')
            """)
    void insertAudio(@Param("projectId") long projectId, @Param("episodeId") long episodeId,
                     @Param("objectKey") byte[] objectKey, @Param("bytes") long bytes,
                     @Param("durationMs") long durationMs, @Param("taskId") long taskId);

    @Insert("""
            INSERT INTO asset (project_id, episode_id, asset_type, bucket, object_key, content_type, size_bytes,
                               start_ms, end_ms, ai_task_id, shot_index, preview_timestamp_ms, original_filename)
            VALUES (#{projectId}, #{episodeId}, 'STORYBOARD', 'video-frames', #{objectKey}, 'image/jpeg',
                    #{bytes}, #{shot.startMs}, #{shot.endMs}, #{taskId}, #{shot.index}, #{shot.previewTimestampMs},
                    #{name})
            """)
    void insertShot(
            @Param("projectId") long projectId, @Param("episodeId") long episodeId,
            @Param("objectKey") byte[] objectKey, @Param("bytes") long bytes,
            @Param("taskId") long taskId, @Param("shot") ShotTiming shot, @Param("name") String name
    );

    record ShotTiming(int index, long startMs, long endMs, long previewTimestampMs) {
    }

    String VISIBLE_ASSETS = """
            FROM asset a JOIN ai_task t ON t.id = a.ai_task_id AND t.episode_id = a.episode_id
                AND t.status = 'SUCCEEDED' AND t.task_type = 'CHARACTER_EXTRACTION'
            WHERE a.project_id = #{projectId} AND a.episode_id = #{episodeId}
                AND a.asset_type = 'CHARACTER_IMAGE' AND a.deleted = 0
                AND t.id = (
                    SELECT MAX(v.id) FROM ai_task v WHERE v.episode_id = a.episode_id
                        AND v.status = 'SUCCEEDED' AND v.task_type = 'CHARACTER_EXTRACTION'
                )
            """;

    @Select("""
            SELECT a.id, a.ai_task_id AS task_id, a.bucket, a.object_key, a.start_ms AS timestamp_ms,
                a.source_frame, a.frame_character_no AS frame_character_number, a.size_bytes
            """ + VISIBLE_ASSETS + " ORDER BY a.start_ms, a.id LIMIT #{size} OFFSET #{offset}")
    List<CharacterAssetEntity> assets(@Param("projectId") long projectId, @Param("episodeId") long episodeId,
                                     @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT COUNT(*) " + VISIBLE_ASSETS)
    long countAssets(@Param("projectId") long projectId, @Param("episodeId") long episodeId);

    record SourceVideo(long projectId, String bucket, byte[] objectKey, long sizeBytes) {
    }
}
