package com.xiye.videoasset.ai.live2d;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface Live2dHistoryMapper {
    String VISIBLE = """
            FROM live2d_model_version v JOIN `character` c ON c.project_id=v.project_id AND c.id=v.character_id
            JOIN asset a ON a.project_id=v.project_id AND a.id=v.id AND a.deleted=0
            LEFT JOIN asset s ON s.project_id=v.project_id AND s.id=v.source_asset_id AND s.deleted=0
                AND s.asset_type IN ('CHARACTER_IMAGE','LIVE2D_SOURCE_IMAGE')
            WHERE v.project_id=#{project} AND c.deleted=0
            """;
    String VERSION = """
            SELECT v.id, v.character_id, v.version_number, v.task_id, v.source_asset_id, v.model_name,
                v.manifest_path, v.saved_at, a.bucket, a.object_key AS objectKey, a.size_bytes AS sizeBytes,
                s.bucket AS sourceBucket, s.object_key AS sourceKey
            """;

    @Select("""
            SELECT EXISTS(SELECT 1 FROM `character` WHERE project_id=#{project} AND id=#{character} AND deleted=0)
            """)
    boolean hasCharacter(@Param("project") long project, @Param("character") long character);

    @Select("""
            SELECT id FROM `character`
            WHERE project_id=#{project} AND id=#{character} AND deleted=0 FOR UPDATE
            """)
    Long lockCharacter(@Param("project") long project, @Param("character") long character);

    @Update("""
            UPDATE `character` SET name=#{name}
            WHERE project_id=#{project} AND id=#{character} AND deleted=0
            """)
    int renameCharacter(@Param("project") long project, @Param("character") long character,
                        @Param("name") String name);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM live2d_task
                WHERE project_id=#{project} AND character_id=#{character}
                    AND (status IN ('QUEUED','RUNNING') OR (status='FAILED' AND artifact_ref IS NOT NULL
                        AND error_code IN ('LIVE2D_SAVE_TIMEOUT','ARTIFACT_MISSING'))))
            """)
    boolean hasPendingTasks(@Param("project") long project, @Param("character") long character);

    @Update("""
            UPDATE `character` SET deleted=1
            WHERE project_id=#{project} AND id=#{character} AND deleted=0
            """)
    int deleteCharacter(@Param("project") long project, @Param("character") long character);

    @Update("""
            UPDATE live2d_character_source SET character_id=NULL
            WHERE project_id=#{project} AND character_id=#{character}
            """)
    int releaseCharacterSources(@Param("project") long project, @Param("character") long character);

    @Insert("""
            INSERT INTO live2d_character_source(project_id,source_asset_id)
            VALUES(#{project},#{source}) ON DUPLICATE KEY UPDATE source_asset_id=live2d_character_source.source_asset_id
            """)
    void reserveSource(@Param("project") long project, @Param("source") long source);

    @Select("""
            SELECT s.character_id FROM live2d_character_source s
            JOIN `character` c ON c.project_id=s.project_id AND c.id=s.character_id AND c.deleted=0
            WHERE s.project_id=#{project} AND s.source_asset_id=#{source}
            """)
    Long sourceCharacter(@Param("project") long project, @Param("source") long source);

    @Update("""
            UPDATE live2d_character_source SET character_id=#{character}
            WHERE project_id=#{project} AND source_asset_id=#{source}
            """)
    void setSourceCharacter(@Param("project") long project, @Param("source") long source,
                            @Param("character") long character);

    @Insert("INSERT INTO `character`(project_id,name) VALUES(#{projectId},#{name})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void createCharacter(Live2dCharacterInsert character);

    @Update("""
            UPDATE `character` SET live2d_version=live2d_version+1
            WHERE project_id=#{project} AND id=#{character} AND deleted=0
            """)
    int advance(@Param("project") long project, @Param("character") long character);

    @Insert("""
            INSERT INTO live2d_model_version
                (id,project_id,character_id,version_number,task_id,source_asset_id,model_name,manifest_path,saved_at)
            SELECT a.id,t.project_id,c.id,c.live2d_version,t.id,t.source_asset_id,t.model_name,t.manifest_path,
                IF(t.status='SUCCEEDED',a.created_at,UTC_TIMESTAMP(6))
            FROM live2d_task t JOIN `character` c ON c.id=#{character} AND c.project_id=t.project_id
            JOIN asset a ON a.id=#{model} AND a.project_id=t.project_id
            WHERE t.id=#{task}
            """)
    int publish(@Param("task") String task, @Param("character") long character, @Param("model") long model);

    @Select("SELECT id, character_id, version_number FROM live2d_model_version WHERE task_id=#{task}")
    Published published(@Param("task") String task);

    @Select("""
            SELECT ranked.character_id AS id, ranked.name, ranked.id AS latestVersionId,
                ranked.source_asset_id AS sourceAssetId, ranked.sourceBucket, ranked.sourceKey
            FROM (
                SELECT c.name,v.character_id,v.id,v.source_asset_id,v.saved_at,
                    s.bucket AS sourceBucket,s.object_key AS sourceKey,
                    ROW_NUMBER() OVER (PARTITION BY v.character_id
                        ORDER BY v.saved_at DESC,v.version_number DESC) AS row_num
            """ + VISIBLE + """
            ) ranked WHERE ranked.row_num=1
            ORDER BY ranked.saved_at DESC,ranked.id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<CharacterSummary> characters(@Param("project") long project, @Param("size") int size,
                                      @Param("offset") long offset);

    @Select("SELECT COUNT(DISTINCT v.character_id) " + VISIBLE)
    long characterCount(@Param("project") long project);

    @Select(VERSION + VISIBLE + """
            AND v.character_id=#{character} ORDER BY v.saved_at DESC,v.version_number DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<Version> versions(@Param("project") long project, @Param("character") long character,
                           @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT COUNT(*) " + VISIBLE + " AND v.character_id=#{character}")
    long versionCount(@Param("project") long project, @Param("character") long character);

    @Select(VERSION + VISIBLE + " AND v.character_id=#{character} AND v.id=#{version}")
    Version detail(@Param("project") long project, @Param("character") long character, @Param("version") long version);

    @Select("""
            SELECT t.id,t.project_id,t.source_asset_id,t.model_name,t.model_asset_id,t.manifest_path,
                a.bucket,a.object_key AS objectKey,a.size_bytes AS sizeBytes
            FROM live2d_task t JOIN project p ON p.id=t.project_id
            LEFT JOIN asset a ON a.id=t.model_asset_id AND a.project_id=t.project_id
                AND a.asset_type='LIVE2D_MODEL' AND a.deleted=0
            LEFT JOIN live2d_model_version v ON v.task_id=t.id
            WHERE t.status='SUCCEEDED' AND v.id IS NULL AND t.history_issue IS NULL AND p.deleted=0
            ORDER BY t.model_asset_id,t.id LIMIT 1
            """)
    Legacy legacy();

    @Select("""
            SELECT id FROM live2d_task WHERE id=#{task} AND status='SUCCEEDED' AND history_issue IS NULL FOR UPDATE
            """)
    String lockLegacy(@Param("task") String task);

    @Update("""
            UPDATE live2d_task t LEFT JOIN live2d_model_version v ON v.task_id=t.id
            SET t.history_issue=#{issue} WHERE t.id=#{task} AND v.id IS NULL
            """)
    int excludeLegacy(@Param("task") String task, @Param("issue") String issue);

    @Select("SELECT path, size_bytes FROM live2d_model_file WHERE model_asset_id=#{model}")
    List<FileEntry> files(@Param("model") long model);

    record Published(long id, long characterId, long versionNumber) {
    }

    record CharacterSummary(long id, String name, long latestVersionId, long sourceAssetId, String sourceBucket,
                            byte[] sourceKey) {
    }

    record Version(long id, long characterId, long versionNumber, String taskId, long sourceAssetId,
                   String modelName, String manifestPath, Instant savedAt, String bucket, byte[] objectKey,
                   long sizeBytes, String sourceBucket, byte[] sourceKey) {
    }

    record Legacy(String id, long projectId, long sourceAssetId, String modelName, Long modelAssetId,
                  String manifestPath, String bucket, byte[] objectKey, Long sizeBytes) {
    }

    record FileEntry(String path, long sizeBytes) {
    }
}
