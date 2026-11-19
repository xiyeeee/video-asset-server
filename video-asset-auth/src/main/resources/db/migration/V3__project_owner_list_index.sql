-- 保留 owner_user_id 外键使用的现有索引，并为个人项目列表补充稳定分页索引。
ALTER TABLE project
    ADD INDEX idx_project_owner_updated (owner_user_id, deleted, updated_at DESC, id DESC);
