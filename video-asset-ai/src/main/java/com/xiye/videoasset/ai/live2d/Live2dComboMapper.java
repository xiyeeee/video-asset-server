package com.xiye.videoasset.ai.live2d;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface Live2dComboMapper {
    @Select("""
            SELECT v.id AS modelVersionId, v.project_id AS projectId, v.task_id AS taskId,
                v.model_name AS modelName, v.manifest_path AS manifestPath,
                a.bucket, a.object_key AS objectKey, a.size_bytes AS sizeBytes
            FROM live2d_model_version v
            JOIN `character` c ON c.project_id=v.project_id AND c.id=v.character_id AND c.deleted=0
            JOIN asset a ON a.project_id=v.project_id AND a.id=v.id
                AND a.asset_type='LIVE2D_MODEL' AND a.deleted=0
            WHERE v.project_id=#{project} AND v.id=#{version}
            """)
    ModelVersion modelVersion(@Param("project") long project, @Param("version") long version);

    @Select("""
            SELECT path, content_type AS contentType, size_bytes AS sizeBytes
            FROM live2d_model_file WHERE model_asset_id=#{model} ORDER BY path
            """)
    List<ModelFile> modelFiles(@Param("model") long model);

    @Select("""
            SELECT d.id AS dialogueId, d.episode_id AS episodeId, d.line_text AS lineText,
                d.start_ms AS startMs, d.end_ms AS endMs, a.id AS audioAssetId, a.bucket,
                a.object_key AS objectKey, a.size_bytes AS sizeBytes
            FROM dialogue d
            JOIN ai_task dt ON dt.id=d.ai_task_id AND dt.episode_id=d.episode_id
                AND dt.task_type='DIALOGUE_EXTRACTION' AND dt.status='SUCCEEDED'
            JOIN asset a ON a.project_id=d.project_id AND a.episode_id=d.episode_id
                AND a.asset_type='AUDIO_TRACK' AND a.deleted=0
            JOIN ai_task at ON at.id=a.ai_task_id AND at.episode_id=a.episode_id
                AND at.task_type='AUDIO_EXTRACTION' AND at.status='SUCCEEDED'
            WHERE d.project_id=#{project} AND a.id=(
                SELECT MAX(latest.id) FROM asset latest
                JOIN ai_task latestTask ON latestTask.id=latest.ai_task_id
                    AND latestTask.task_type='AUDIO_EXTRACTION' AND latestTask.status='SUCCEEDED'
                WHERE latest.project_id=d.project_id AND latest.episode_id=d.episode_id
                    AND latest.asset_type='AUDIO_TRACK' AND latest.deleted=0
            )
            ORDER BY d.start_ms, d.id LIMIT #{size} OFFSET #{offset}
            """)
    List<DialogueSource> dialogueSources(@Param("project") long project, @Param("size") int size,
                                         @Param("offset") long offset);

    @Select("""
            SELECT COUNT(*) FROM dialogue d
            JOIN ai_task dt ON dt.id=d.ai_task_id AND dt.episode_id=d.episode_id
                AND dt.task_type='DIALOGUE_EXTRACTION' AND dt.status='SUCCEEDED'
            WHERE d.project_id=#{project} AND EXISTS (
                SELECT 1 FROM asset a JOIN ai_task at ON at.id=a.ai_task_id AND at.episode_id=a.episode_id
                    AND at.task_type='AUDIO_EXTRACTION' AND at.status='SUCCEEDED'
                WHERE a.project_id=d.project_id AND a.episode_id=d.episode_id
                    AND a.asset_type='AUDIO_TRACK' AND a.deleted=0
            )
            """)
    long dialogueSourceCount(@Param("project") long project);

    @Select("""
            SELECT d.id AS dialogueId, d.episode_id AS episodeId, d.line_text AS lineText,
                d.start_ms AS startMs, d.end_ms AS endMs, a.id AS audioAssetId, a.bucket,
                a.object_key AS objectKey, a.size_bytes AS sizeBytes
            FROM dialogue d
            JOIN ai_task dt ON dt.id=d.ai_task_id AND dt.episode_id=d.episode_id
                AND dt.task_type='DIALOGUE_EXTRACTION' AND dt.status='SUCCEEDED'
            JOIN asset a ON a.project_id=d.project_id AND a.episode_id=d.episode_id
                AND a.id=#{audio} AND a.asset_type='AUDIO_TRACK' AND a.deleted=0
            JOIN ai_task at ON at.id=a.ai_task_id AND at.episode_id=a.episode_id
                AND at.task_type='AUDIO_EXTRACTION' AND at.status='SUCCEEDED'
            WHERE d.project_id=#{project} AND d.id=#{dialogue}
            """)
    DialogueSource dialogueSource(@Param("project") long project, @Param("dialogue") long dialogue,
                                  @Param("audio") long audio);

    @Insert("""
            INSERT IGNORE INTO live2d_combo_compilation
                (id,project_id,model_version_id,request_id,content_hash,name,duration_ms,
                 timeline_json,playback_json,issues_json,artifact_bucket,artifact_key,artifact_size,
                 artifact_sha256,compile_status)
            VALUES (#{id},#{project},#{modelVersion},#{requestId},#{hash},#{name},#{duration},
                    #{timeline},#{playback},#{issues},#{artifactBucket},#{artifactKey},#{artifactSize},
                    #{artifactSha256},#{status})
            """)
    int insertCompilation(CompilationInsert compilation);

    @Select("""
            SELECT id, project_id AS projectId, model_version_id AS modelVersionId,
                request_id AS requestId, content_hash AS contentHash, name, duration_ms AS durationMs,
                timeline_json AS timelineJson, playback_json AS playbackJson, issues_json AS issuesJson,
                artifact_bucket AS artifactBucket,artifact_key AS artifactKey,artifact_size AS artifactSize,
                artifact_sha256 AS artifactSha256,compile_status AS status, created_at AS createdAt
            FROM live2d_combo_compilation WHERE project_id=#{project} AND id=#{id}
            """)
    Compilation compilation(@Param("project") long project, @Param("id") String id);

    @Select("""
            SELECT id, project_id AS projectId, model_version_id AS modelVersionId,
                request_id AS requestId, content_hash AS contentHash, name, duration_ms AS durationMs,
                timeline_json AS timelineJson, playback_json AS playbackJson, issues_json AS issuesJson,
                artifact_bucket AS artifactBucket,artifact_key AS artifactKey,artifact_size AS artifactSize,
                artifact_sha256 AS artifactSha256,compile_status AS status, created_at AS createdAt
            FROM live2d_combo_compilation WHERE project_id=#{project} AND request_id=#{requestId}
            """)
    Compilation compilationByRequest(@Param("project") long project, @Param("requestId") String requestId);

    @Insert("""
            INSERT IGNORE INTO live2d_combo_save_request
                (project_id,request_id,combo_id,compilation_id,content_hash,operation,status)
            VALUES (#{project},#{requestId},#{comboId},#{compilationId},#{contentHash},#{operation},'PROCESSING')
            """)
    int reserveSaveRequest(SaveRequestInsert request);

    @Select("""
            SELECT project_id AS projectId, request_id AS requestId, combo_id AS comboId,
                compilation_id AS compilationId, content_hash AS contentHash,
                operation, status, error_code AS errorCode
            FROM live2d_combo_save_request
            WHERE project_id=#{project} AND request_id=#{requestId} FOR UPDATE
            """)
    SaveRequest lockSaveRequest(@Param("project") long project, @Param("requestId") String requestId);

    @Select("""
            SELECT project_id AS projectId, request_id AS requestId, combo_id AS comboId,
                compilation_id AS compilationId, content_hash AS contentHash,
                operation, status, error_code AS errorCode
            FROM live2d_combo_save_request WHERE project_id=#{project} AND request_id=#{requestId}
            """)
    SaveRequest saveRequest(@Param("project") long project, @Param("requestId") String requestId);

    @Update("""
            UPDATE live2d_combo_save_request SET status='PROCESSING',error_code=NULL
            WHERE project_id=#{project} AND request_id=#{requestId} AND status='FAILED'
            """)
    int retrySaveRequest(@Param("project") long project, @Param("requestId") String requestId);

    @Update("""
            UPDATE live2d_combo_save_request SET status='FAILED',error_code='COMBO_SAVE_INTERRUPTED'
            WHERE project_id=#{project} AND request_id=#{requestId} AND status='PROCESSING'
                AND updated_at<TIMESTAMPADD(SECOND,-120,UTC_TIMESTAMP(6))
            """)
    int failStaleSaveRequest(@Param("project") long project, @Param("requestId") String requestId);

    @Update("""
            UPDATE live2d_combo_save_request SET status='FAILED',error_code=#{error}
            WHERE project_id=#{project} AND request_id=#{requestId} AND status='PROCESSING'
            """)
    int failSaveRequest(@Param("project") long project, @Param("requestId") String requestId,
                        @Param("error") String error);

    @Insert("""
            INSERT INTO live2d_combo_motion
                (id,project_id,model_version_id,name,duration_ms,content_hash,timeline_json,playback_json,
                 playback_bucket,playback_key,playback_size,status)
            VALUES (#{id},#{project},#{modelVersion},#{name},#{duration},#{hash},#{timeline},#{playback},
                    #{bucket},#{key},#{size},'AVAILABLE')
            """)
    int insertCombo(ComboWrite combo);

    @Update("""
            UPDATE live2d_combo_motion SET name=#{name},duration_ms=#{duration},content_hash=#{hash},
                timeline_json=#{timeline},playback_json=#{playback},playback_bucket=#{bucket},
                playback_key=#{key},playback_size=#{size},status='AVAILABLE'
            WHERE id=#{id} AND project_id=#{project} AND model_version_id=#{modelVersion}
            """)
    int updateCombo(ComboWrite combo);

    @Update("""
            UPDATE live2d_combo_save_request SET status='SAVED',error_code=NULL
            WHERE project_id=#{project} AND request_id=#{requestId} AND status='PROCESSING'
            """)
    int completeSaveRequest(@Param("project") long project, @Param("requestId") String requestId);

    @Select("""
            SELECT id,project_id AS projectId,model_version_id AS modelVersionId,name,
                duration_ms AS durationMs,content_hash AS contentHash,timeline_json AS timelineJson,
                playback_json AS playbackJson,playback_bucket AS playbackBucket,
                playback_key AS playbackKey,playback_size AS playbackSize,status,
                saved_at AS savedAt,updated_at AS updatedAt
            FROM live2d_combo_motion WHERE project_id=#{project} AND id=#{id} AND status='AVAILABLE'
            """)
    Combo combo(@Param("project") long project, @Param("id") String id);

    @Select("""
            SELECT id,model_version_id AS modelVersionId,name,duration_ms AS durationMs,
                status,saved_at AS savedAt,updated_at AS updatedAt
            FROM live2d_combo_motion WHERE project_id=#{project} AND status='AVAILABLE'
            ORDER BY updated_at DESC,id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<ComboSummary> combos(@Param("project") long project, @Param("size") int size,
                              @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM live2d_combo_motion WHERE project_id=#{project} AND status='AVAILABLE'")
    long comboCount(@Param("project") long project);

    record ModelVersion(long modelVersionId, long projectId, String taskId, String modelName, String manifestPath,
                        String bucket, byte[] objectKey, long sizeBytes) {
    }

    record ModelFile(String path, String contentType, long sizeBytes) {
    }

    record DialogueSource(long dialogueId, long episodeId, String lineText, long startMs, long endMs,
                          long audioAssetId, String bucket, byte[] objectKey, long sizeBytes) {
    }

    record CompilationInsert(String id, long project, long modelVersion, String requestId, String hash,
                             String name, long duration, String timeline, String playback, String issues,
                             String artifactBucket, byte[] artifactKey, Long artifactSize, String artifactSha256,
                             String status) {
    }

    record Compilation(String id, long projectId, long modelVersionId, String requestId, String contentHash,
                       String name, long durationMs, String timelineJson, String playbackJson, String issuesJson,
                       String artifactBucket, byte[] artifactKey, Long artifactSize, String artifactSha256,
                       String status, Instant createdAt) {
    }

    record SaveRequestInsert(long project, String requestId, String comboId, String compilationId,
                             String contentHash, String operation) {
    }

    record SaveRequest(long projectId, String requestId, String comboId, String compilationId,
                       String contentHash, String operation, String status, String errorCode) {
    }

    record ComboWrite(String id, long project, long modelVersion, String name, long duration, String hash,
                      String timeline, String playback, String bucket, byte[] key, long size) {
    }

    record Combo(String id, long projectId, long modelVersionId, String name, long durationMs, String contentHash,
                 String timelineJson, String playbackJson, String playbackBucket, byte[] playbackKey,
                 long playbackSize, String status, Instant savedAt, Instant updatedAt) {
    }

    record ComboSummary(String id, long modelVersionId, String name, long durationMs, String status,
                        Instant savedAt, Instant updatedAt) {
    }
}
