package com.xiye.videoasset.ai.live2d;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface Live2dMotionMapper {
    String ASSET_COLUMNS = """
            id,project_id AS projectId,code,action_type AS actionType,category,name,description,official,
            current_version_id AS currentVersionId,draft_version_id AS draftVersionId,
            version_sequence AS versionSequence,revision,deleted,created_at AS createdAt,updated_at AS updatedAt
            """;
    String VERSION_COLUMNS = """
            v.id,v.motion_id AS motionId,v.version_number AS versionNumber,v.status,v.revision,
            v.definition_json AS definitionJson,v.definition_hash AS definitionHash,
            v.created_by AS createdBy,v.created_at AS createdAt,v.published_at AS publishedAt
            """;

    @Select("""
            <script>
            SELECT
            """ + ASSET_COLUMNS + """
            FROM live2d_motion_asset
            WHERE deleted=0 AND (project_id IS NULL OR project_id=#{project})
                <if test="type != null">AND action_type=#{type}</if>
                <if test="category != null">AND category=#{category}</if>
                <if test="keyword != null and keyword != ''">
                    AND (name LIKE CONCAT('%',#{keyword},'%') OR code LIKE CONCAT('%',#{keyword},'%'))
                </if>
            ORDER BY official DESC,category,name,id LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<ActionAsset> assets(@Param("project") long project, @Param("type") String type,
                             @Param("category") String category, @Param("keyword") String keyword,
                             @Param("size") int size, @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM live2d_motion_asset
            WHERE deleted=0 AND (project_id IS NULL OR project_id=#{project})
                <if test="type != null">AND action_type=#{type}</if>
                <if test="category != null">AND category=#{category}</if>
                <if test="keyword != null and keyword != ''">
                    AND (name LIKE CONCAT('%',#{keyword},'%') OR code LIKE CONCAT('%',#{keyword},'%'))
                </if>
            </script>
            """)
    long assetCount(@Param("project") long project, @Param("type") String type,
                    @Param("category") String category, @Param("keyword") String keyword);

    @Select("SELECT " + ASSET_COLUMNS + " FROM live2d_motion_asset WHERE id=#{id} AND deleted=0 "
            + "AND (project_id IS NULL OR project_id=#{project})")
    ActionAsset asset(@Param("project") long project, @Param("id") String id);

    @Select("SELECT " + VERSION_COLUMNS + " FROM live2d_motion_version v "
            + "JOIN live2d_motion_asset a ON a.id=v.motion_id "
            + "WHERE a.id=#{asset} AND a.deleted=0 AND v.id=#{version} "
            + "AND (a.project_id IS NULL OR a.project_id=#{project})")
    MotionVersion version(@Param("project") long project, @Param("asset") String asset,
                          @Param("version") String version);

    @Select("SELECT " + VERSION_COLUMNS + " FROM live2d_motion_version v "
            + "JOIN live2d_motion_asset a ON a.id=v.motion_id "
            + "WHERE v.id=#{version} AND v.status='PUBLISHED' AND a.deleted=0 "
            + "AND (a.project_id IS NULL OR a.project_id=#{project})")
    MotionVersion publishedVersion(@Param("project") long project, @Param("version") String version);

    @Select("SELECT " + VERSION_COLUMNS + " FROM live2d_motion_version v "
            + "JOIN live2d_motion_asset a ON a.id=v.motion_id "
            + "WHERE a.id=#{asset} AND a.deleted=0 AND (a.project_id IS NULL OR a.project_id=#{project}) "
            + "ORDER BY (v.status='PUBLISHED') DESC,v.version_number DESC,v.created_at DESC")
    List<MotionVersion> versions(@Param("project") long project, @Param("asset") String asset);

    @Select("SELECT " + ASSET_COLUMNS + " FROM live2d_motion_asset WHERE id=#{asset} AND project_id=#{project} "
            + "AND deleted=0 FOR UPDATE")
    ActionAsset lockOwnedAsset(@Param("project") long project, @Param("asset") String asset);

    @Insert("""
            INSERT INTO live2d_motion_asset
                (id,project_id,code,action_type,category,name,description,official,version_sequence,revision)
            VALUES (#{id},#{project},#{code},#{type},#{category},#{name},#{description},0,0,0)
            """)
    int insertAsset(NewAsset asset);

    @Insert("""
            INSERT INTO live2d_motion_version(id,motion_id,version_number,status,revision,definition_json,
                                              definition_hash,created_by)
            VALUES(#{id},#{motionId},NULL,'DRAFT',#{revision},#{definition},#{hash},#{createdBy})
            """)
    int insertDraft(NewVersion version);

    @Update("""
            UPDATE live2d_motion_asset SET draft_version_id=#{version},revision=revision+1
            WHERE id=#{asset} AND project_id=#{project} AND deleted=0 AND draft_version_id IS NULL
            """)
    int setDraft(@Param("project") long project, @Param("asset") String asset,
                 @Param("version") String version);

    @Update("""
            UPDATE live2d_motion_version SET definition_json=#{definition},definition_hash=#{hash},
                revision=revision+1
            WHERE id=#{version} AND motion_id=#{asset} AND status='DRAFT' AND revision=#{expected}
            """)
    int updateDraft(@Param("asset") String asset, @Param("version") String version,
                    @Param("expected") long expected, @Param("definition") String definition,
                    @Param("hash") String hash);

    @Update("""
            UPDATE live2d_motion_asset SET revision=revision+1
            WHERE id=#{asset} AND project_id=#{project} AND revision=#{expected} AND deleted=0
            """)
    int advanceRevision(@Param("project") long project, @Param("asset") String asset,
                        @Param("expected") long expected);

    @Update("""
            UPDATE live2d_motion_asset SET version_sequence=version_sequence+1
            WHERE id=#{asset} AND project_id=#{project} AND deleted=0
            """)
    int advanceSequence(@Param("project") long project, @Param("asset") String asset);

    @Select("SELECT version_sequence FROM live2d_motion_asset WHERE id=#{asset} AND project_id=#{project}")
    long versionSequence(@Param("project") long project, @Param("asset") String asset);

    @Update("""
            UPDATE live2d_motion_version SET version_number=#{number},status='PUBLISHED',
                published_at=UTC_TIMESTAMP(6)
            WHERE id=#{version} AND motion_id=#{asset} AND status='DRAFT'
            """)
    int publishVersion(@Param("asset") String asset, @Param("version") String version,
                       @Param("number") long number);

    @Update("""
            UPDATE live2d_motion_asset SET current_version_id=#{version},draft_version_id=NULL,revision=revision+1
            WHERE id=#{asset} AND project_id=#{project} AND draft_version_id=#{version} AND deleted=0
            """)
    int setPublishedVersion(@Param("project") long project, @Param("asset") String asset,
                            @Param("version") String version);

    @Select("SELECT " + VERSION_COLUMNS + " FROM live2d_motion_version v "
            + "JOIN live2d_motion_asset a ON a.id=v.motion_id "
            + "WHERE a.id=#{asset} AND a.draft_version_id=v.id AND a.project_id=#{project} FOR UPDATE")
    MotionVersion lockDraft(@Param("project") long project, @Param("asset") String asset);

    @Delete("DELETE FROM live2d_motion_reference WHERE motion_version_id=#{version}")
    int deleteReferences(@Param("version") String version);

    @Insert("""
            <script>
            INSERT INTO live2d_motion_reference
                (motion_version_id,clip_id,reference_type,source_motion_version_id,source_asset_id,
                 replacement_motion_version_id,track_code,start_ms,duration_ms,source_start_ms)
            VALUES
            <foreach collection="references" item="reference" separator=",">
                (#{version},#{reference.clipId},#{reference.referenceType},#{reference.sourceMotionVersionId},
                 #{reference.sourceAssetId},#{reference.replacementVersionId},#{reference.trackCode},
                 #{reference.startMs},#{reference.durationMs},#{reference.sourceStartMs})
            </foreach>
            </script>
            """)
    int insertReferences(@Param("version") String version, @Param("references") List<MotionReference> references);

    @Select("""
            SELECT motion_version_id AS motionVersionId,clip_id AS clipId,reference_type AS referenceType,
                source_motion_version_id AS sourceMotionVersionId,source_asset_id AS sourceAssetId,
                replacement_motion_version_id AS replacementVersionId,track_code AS trackCode,
                start_ms AS startMs,duration_ms AS durationMs,source_start_ms AS sourceStartMs
            FROM live2d_motion_reference WHERE motion_version_id=#{version} ORDER BY start_ms,clip_id
            """)
    List<MotionReference> references(@Param("version") String version);

    @Select("""
            SELECT """ + VERSION_COLUMNS + """
            FROM live2d_motion_reference r
            JOIN live2d_motion_version v ON v.id=r.source_motion_version_id
            JOIN live2d_motion_asset a ON a.id=v.motion_id
            WHERE r.motion_version_id=#{version} AND r.clip_id=#{clip}
                AND v.status='PUBLISHED' AND a.deleted=0
                AND (a.project_id IS NULL OR a.project_id=#{project})
            """)
    MotionVersion referenceVersion(@Param("project") long project, @Param("version") String version,
                                   @Param("clip") String clip);

    @Select("""
            SELECT id,project_id AS projectId,episode_id AS episodeId,bucket,object_key AS objectKey,
                size_bytes AS sizeBytes,end_ms AS durationMs
            FROM asset WHERE id=#{asset} AND project_id=#{project} AND asset_type='AUDIO_TRACK' AND deleted=0
            """)
    AudioAsset audioAsset(@Param("project") long project, @Param("asset") long asset);

    @Insert("""
            INSERT INTO live2d_model_parameter
                (model_version_id,parameter_id,min_value,max_value,default_value,active,
                 active_decreasing,active_increasing)
            VALUES(#{modelVersionId},#{id},#{min},#{max},#{defaultValue},#{active},
                   #{activeDecreasing},#{activeIncreasing})
            ON DUPLICATE KEY UPDATE min_value=VALUES(min_value),max_value=VALUES(max_value),
                default_value=VALUES(default_value),active=VALUES(active),
                active_decreasing=VALUES(active_decreasing),active_increasing=VALUES(active_increasing)
            """)
    int upsertParameter(ModelParameter parameter);

    @Delete("""
            DELETE FROM live2d_model_capability_mapping
            WHERE model_version_id=#{modelVersionId} AND source='PYTHON' AND mapping_status<>'CONFIRMED'
            """)
    int clearPythonMappings(@Param("modelVersionId") long modelVersionId);

    @Insert("""
            INSERT INTO live2d_model_capability_mapping
                (model_version_id,capability_id,mapping_status,parameter_ids,missing_parameter_ids,
                 evidence_json,asset_refs_json,part_ids_json,reason,analysis_fingerprint,source,updated_by)
            VALUES(#{modelVersionId},#{id},#{status},#{parameterIds},#{missingParameterIds},
                   #{evidenceJson},#{assetRefsJson},#{partIdsJson},#{reason},#{analysisFingerprint},'PYTHON',NULL)
            ON DUPLICATE KEY UPDATE
                mapping_status=IF(mapping_status='CONFIRMED',mapping_status,VALUES(mapping_status)),
                parameter_ids=IF(mapping_status='CONFIRMED',parameter_ids,VALUES(parameter_ids)),
                missing_parameter_ids=IF(mapping_status='CONFIRMED',missing_parameter_ids,
                                         VALUES(missing_parameter_ids)),
                evidence_json=IF(mapping_status='CONFIRMED',evidence_json,VALUES(evidence_json)),
                asset_refs_json=IF(mapping_status='CONFIRMED',asset_refs_json,VALUES(asset_refs_json)),
                part_ids_json=IF(mapping_status='CONFIRMED',part_ids_json,VALUES(part_ids_json)),
                reason=IF(mapping_status='CONFIRMED',reason,VALUES(reason)),
                analysis_fingerprint=IF(mapping_status='CONFIRMED',analysis_fingerprint,
                                        VALUES(analysis_fingerprint)),
                source=IF(mapping_status='CONFIRMED',source,'PYTHON')
            """)
    int upsertCapabilityMapping(CapabilityMappingWrite mapping);

    @Select("""
            SELECT model_version_id AS modelVersionId,capability_id AS capabilityId,
                mapping_status AS status,parameter_ids AS parameterIds,missing_parameter_ids AS missingParameterIds,
                evidence_json AS evidenceJson,asset_refs_json AS assetRefsJson,part_ids_json AS partIdsJson,
                reason,analysis_fingerprint AS analysisFingerprint,source,updated_by AS updatedBy,
                updated_at AS updatedAt
            FROM live2d_model_capability_mapping WHERE model_version_id=#{modelVersionId}
            ORDER BY capability_id
            """)
    List<CapabilityMapping> capabilityMappings(@Param("modelVersionId") long modelVersionId);

    @Select("""
            SELECT model_version_id AS modelVersionId,parameter_id AS id,min_value AS min,
                max_value AS max,default_value AS defaultValue,active,active_decreasing AS activeDecreasing,
                active_increasing AS activeIncreasing
            FROM live2d_model_parameter WHERE model_version_id=#{modelVersionId} ORDER BY parameter_id
            """)
    List<ModelParameter> modelParameters(@Param("modelVersionId") long modelVersionId);

    @Update("""
            UPDATE live2d_model_capability_mapping SET mapping_status=#{status},parameter_ids=#{parameterIds},
                missing_parameter_ids=#{missingParameterIds},reason=#{reason},source='USER',updated_by=#{user}
            WHERE model_version_id=#{modelVersionId} AND capability_id=#{capabilityId}
            """)
    int updateCapabilityMapping(@Param("modelVersionId") long modelVersionId,
                                @Param("capabilityId") String capabilityId,
                                @Param("status") String status, @Param("parameterIds") String parameterIds,
                                @Param("missingParameterIds") String missingParameterIds,
                                @Param("reason") String reason, @Param("user") long user);

    @Insert("""
            INSERT INTO live2d_model_analysis_failure(model_version_id,error_code,error_message,retry_count)
            VALUES(#{modelVersionId},#{errorCode},#{errorMessage},1)
            ON DUPLICATE KEY UPDATE error_code=VALUES(error_code),error_message=VALUES(error_message),
                retry_count=retry_count+1,failed_at=UTC_TIMESTAMP(6)
            """)
    int recordAnalysisFailure(@Param("modelVersionId") long modelVersionId, @Param("errorCode") String errorCode,
                              @Param("errorMessage") String errorMessage);

    @Delete("DELETE FROM live2d_model_analysis_failure WHERE model_version_id=#{modelVersionId}")
    int clearAnalysisFailure(@Param("modelVersionId") long modelVersionId);

    @Select("""
            SELECT model_version_id AS modelVersionId,error_code AS errorCode,error_message AS errorMessage,
                retry_count AS retryCount,failed_at AS failedAt,updated_at AS updatedAt
            FROM live2d_model_analysis_failure WHERE model_version_id=#{modelVersionId}
            """)
    AnalysisFailure analysisFailure(@Param("modelVersionId") long modelVersionId);

    record ActionAsset(String id, Long projectId, String code, String actionType, String category,
                       String name, String description, boolean official, String currentVersionId,
                       String draftVersionId, long versionSequence, long revision, boolean deleted,
                       Instant createdAt, Instant updatedAt) {
    }

    record MotionVersion(String id, String motionId, Long versionNumber, String status, long revision,
                         String definitionJson, String definitionHash, Long createdBy,
                         Instant createdAt, Instant publishedAt) {
    }

    record NewAsset(String id, long project, String code, String type, String category,
                    String name, String description) {
    }

    record NewVersion(String id, String motionId, long revision, String definition, String hash, Long createdBy) {
    }

    record MotionReference(String motionVersionId, String clipId, String referenceType,
                           String sourceMotionVersionId, Long sourceAssetId, String replacementVersionId,
                           String trackCode, long startMs,
                           long durationMs, Long sourceStartMs) {
    }

    record AudioAsset(long id, long projectId, Long episodeId, String bucket, byte[] objectKey,
                      long sizeBytes, Long durationMs) {
    }

    record ModelParameter(long modelVersionId, String id, Double min, Double max, Double defaultValue,
                          Boolean active, Boolean activeDecreasing, Boolean activeIncreasing) {
    }

    record CapabilityMappingWrite(long modelVersionId, String id, String status,
                                  String parameterIds, String missingParameterIds, String evidenceJson,
                                  String assetRefsJson, String partIdsJson, String reason,
                                  String analysisFingerprint) {
    }

    record CapabilityMapping(long modelVersionId, String capabilityId, String status,
                             String parameterIds, String missingParameterIds, String evidenceJson,
                             String assetRefsJson, String partIdsJson, String reason, String analysisFingerprint,
                             String source,
                             Long updatedBy, Instant updatedAt) {
    }

    record AnalysisFailure(long modelVersionId, String errorCode, String errorMessage, int retryCount,
                           Instant failedAt, Instant updatedAt) {
    }
}
