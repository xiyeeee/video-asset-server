package com.xiye.videoasset.ai.live2d;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface Live2dMapper {
    String COLUMNS = """
            id, project_id, source_asset_id, model_name, model_asset_id, status, stage, manifest_path,
            artifact_ref, error_code, error_stage, lease_version, submission_count
            """;
    String ACTIVE = """
            WHERE id=#{id} AND lease_version=#{lease} AND status IN ('QUEUED','RUNNING')
                AND deadline>UTC_TIMESTAMP(6)
            """;

    @Select("SELECT EXISTS(SELECT 1 FROM project WHERE id=#{id} AND owner_user_id=#{owner} AND deleted=0)")
    boolean owns(@Param("id") long id, @Param("owner") long owner);

    @Select("""
            SELECT id, bucket, object_key, content_type, size_bytes
            FROM asset WHERE id=#{id} AND project_id=#{project} AND asset_type=#{type} AND deleted=0
            """)
    Image image(@Param("project") long project, @Param("id") long id, @Param("type") String type);

    @Select("""
            SELECT id, bucket, object_key, content_type, size_bytes FROM asset
            WHERE id=#{id} AND project_id=#{project} AND deleted=0
                AND asset_type IN ('CHARACTER_IMAGE','LIVE2D_SOURCE_IMAGE')
            """)
    Image source(@Param("project") long project, @Param("id") long id);

    @Insert("""
            INSERT INTO live2d_task (id,project_id,source_asset_id,model_name,status,next_poll,deadline)
            VALUES (#{id},#{project},#{source},#{name},'QUEUED',UTC_TIMESTAMP(6),
                    TIMESTAMPADD(SECOND,#{timeout},UTC_TIMESTAMP(6)))
            """)
    void create(@Param("id") String id, @Param("project") long project, @Param("source") long source,
                @Param("name") String name, @Param("timeout") long timeout);

    @Select("SELECT " + COLUMNS + " FROM live2d_task WHERE id=#{id} AND project_id=#{project}")
    Live2dTask get(@Param("id") String id, @Param("project") long project);

    @Select("SELECT " + COLUMNS + """
            FROM live2d_task WHERE status IN ('QUEUED','RUNNING')
                AND next_poll<=UTC_TIMESTAMP(6) AND deadline>UTC_TIMESTAMP(6)
            ORDER BY next_poll,id LIMIT 1
            """)
    Live2dTask due();

    @Update("""
            UPDATE live2d_task SET lease_version=lease_version+1, next_poll=UTC_TIMESTAMP(6)+INTERVAL 2 MINUTE
            """ + ACTIVE + " AND next_poll<=UTC_TIMESTAMP(6)")
    int claim(@Param("id") String id, @Param("lease") int lease);

    @Update("""
            UPDATE live2d_task SET status='FAILED', error_code='LIVE2D_TASK_TIMEOUT', error_stage=stage, next_poll=NULL
            WHERE status IN ('QUEUED','RUNNING') AND deadline<=UTC_TIMESTAMP(6)
            """)
    int expire();

    @Update("UPDATE live2d_task SET submission_count=submission_count+1 " + ACTIVE)
    int submitted(@Param("id") String id, @Param("lease") int lease);

    @Update("UPDATE live2d_task SET next_poll=TIMESTAMPADD(SECOND,#{delay},UTC_TIMESTAMP(6)) " + ACTIVE)
    int schedule(@Param("id") String id, @Param("lease") int lease, @Param("delay") long delay);

    @Update("""
            UPDATE live2d_task SET status=#{status}, stage=#{stage},
                manifest_path=#{manifest}, artifact_ref=#{artifact},
                next_poll=TIMESTAMPADD(SECOND,#{delay},UTC_TIMESTAMP(6))
            """ + ACTIVE)
    void progress(@Param("id") String id, @Param("lease") int lease, @Param("status") Live2dTask.Status status,
                  @Param("stage") String stage, @Param("manifest") String manifest,
                  @Param("artifact") String artifact, @Param("delay") long delay);

    @Update("""
            UPDATE live2d_task SET status='FAILED', stage=COALESCE(#{stage},stage),
                error_code=#{error}, error_stage=COALESCE(#{stage},stage), next_poll=NULL
            """ + ACTIVE)
    int fail(@Param("id") String id, @Param("lease") int lease,
             @Param("error") String error, @Param("stage") String stage);

    @Select("SELECT id FROM live2d_task " + ACTIVE + " FOR UPDATE")
    String lock(@Param("id") String id, @Param("lease") int lease);

    @Update("UPDATE live2d_task SET status='SUCCEEDED', model_asset_id=#{model}, next_poll=NULL " + ACTIVE)
    int succeed(@Param("id") String id, @Param("lease") int lease, @Param("model") long model);

    @Insert("""
            INSERT INTO live2d_model_file(model_asset_id,path,content_type,size_bytes)
            VALUES (#{model},#{path},#{type},#{size})
            """)
    void file(@Param("model") long model, @Param("path") String path,
              @Param("type") String type, @Param("size") long size);

    @Select("""
            SELECT t.id, f.content_type, f.size_bytes
            FROM live2d_model_file f JOIN asset a ON a.id=f.model_asset_id AND a.deleted=0
            JOIN project p ON p.id=a.project_id AND p.deleted=0
            JOIN live2d_task t ON t.model_asset_id=a.id AND t.project_id=p.id AND t.status='SUCCEEDED'
            WHERE p.id=#{project} AND a.id=#{model} AND f.path=#{path}
            """)
    Resource resource(@Param("project") long project, @Param("model") long model, @Param("path") String path);

    record Image(long id, String bucket, byte[] key, String contentType, long bytes) {
    }

    record Resource(String taskId, String contentType, long bytes) {
    }
}
