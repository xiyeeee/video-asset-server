package com.xiye.videoasset.ai.live2d;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class Live2dAssets {
    static final String BUCKET = "video-assets";
    static final String SOURCE = "LIVE2D_SOURCE_IMAGE";
    static final String MODEL = "LIVE2D_MODEL";
    private final JdbcClient jdbc;

    Live2dAssets(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    long insert(long project, String type, String key, String name, String contentType, long size) {
        GeneratedKeyHolder id = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO asset(project_id,asset_type,bucket,object_key,original_filename,content_type,size_bytes)
                VALUES (:project,:type,:bucket,:key,:name,:contentType,:size)
                """).param("project", project).param("type", type).param("bucket", BUCKET)
                .param("key", key.getBytes(StandardCharsets.UTF_8)).param("name", name)
                .param("contentType", contentType).param("size", size).update(id, "id");
        return Objects.requireNonNull(id.getKey()).longValue();
    }

    static String root(long project, String task) {
        return "projects/" + project + "/live2d/" + task + "/";
    }
}
