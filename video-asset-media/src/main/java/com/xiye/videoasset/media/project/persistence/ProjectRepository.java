package com.xiye.videoasset.media.project.persistence;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {
    private static final String SELECT = "SELECT id, name, description, created_at, updated_at FROM project";
    private static final String OWNED = " WHERE owner_user_id = :ownerId AND deleted = 0";
    private static final String SEARCH = """
             AND (name LIKE :keyword ESCAPE '!' OR description LIKE :keyword ESCAPE '!')
            """;
    private static final RowMapper<ProjectRow> PROJECT = (row, rowNumber) -> new ProjectRow(
            row.getLong("id"), row.getString("name"), row.getString("description"),
            row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());

    private final JdbcClient jdbc;

    public ProjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isActiveUser(long ownerId) {
        return jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM sys_user WHERE id = :ownerId AND deleted = 0 AND status = 'ACTIVE'
                )
                """)
                .param("ownerId", ownerId).query(Boolean.class).single();
    }

    public long insert(long ownerId, String name, String description) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        int inserted = jdbc.sql("""
                INSERT INTO project (owner_user_id, name, description)
                SELECT id, :name, :description
                FROM sys_user
                WHERE id = :ownerId AND deleted = 0 AND status = 'ACTIVE'
                """)
                .param("ownerId", ownerId)
                .param("name", name)
                .param("description", description)
                .update(keys, "id");
        return inserted == 0 ? 0 : Objects.requireNonNull(keys.getKey()).longValue();
    }

    public List<ProjectRow> list(long ownerId, String keyword, int size, long offset) {
        return ownedQuery(SELECT, ownerId, keyword, " ORDER BY updated_at DESC, id DESC LIMIT :size OFFSET :offset")
                .param("size", size)
                .param("offset", offset)
                .query(PROJECT)
                .list();
    }

    public long count(long ownerId, String keyword) {
        return ownedQuery("SELECT COUNT(*) FROM project", ownerId, keyword, "").query(Long.class).single();
    }

    public Optional<ProjectRow> find(long ownerId, long projectId) {
        return jdbc.sql(SELECT + OWNED + " AND id = :projectId")
                .param("ownerId", ownerId)
                .param("projectId", projectId)
                .query(PROJECT)
                .optional();
    }

    public void update(long ownerId, long projectId, String name, String description) {
        jdbc.sql("""
                UPDATE project SET name = COALESCE(:name, name), description = COALESCE(:description, description)
                """ + OWNED + " AND id = :projectId")
                .param("ownerId", ownerId)
                .param("projectId", projectId)
                .param("name", name, Types.VARCHAR)
                .param("description", description, Types.VARCHAR)
                .update();
    }

    private JdbcClient.StatementSpec ownedQuery(String statement, long ownerId, String keyword, String suffix) {
        if (keyword.isEmpty()) {
            return jdbc.sql(statement + OWNED + suffix).param("ownerId", ownerId);
        }

        // 保留用户输入的搜索含义，百分号和下划线按字面文本匹配。
        String pattern = "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return jdbc.sql(statement + OWNED + SEARCH + suffix).param("ownerId", ownerId).param("keyword", pattern);
    }

    public record ProjectRow(long id, String name, String description, Instant createdAt, Instant updatedAt) {
    }
}
