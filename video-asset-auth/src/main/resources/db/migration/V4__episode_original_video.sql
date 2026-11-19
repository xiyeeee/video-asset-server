ALTER TABLE asset
    ADD COLUMN original_filename VARCHAR(255) NULL,
    ADD UNIQUE INDEX uk_asset_project_episode_id (project_id, episode_id, id);

ALTER TABLE video_episode
    ADD COLUMN source_asset_id BIGINT NULL,
    ADD COLUMN upload_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN upload_fingerprint BINARY(32) NULL,
    ADD UNIQUE INDEX uk_episode_project_upload_key (project_id, upload_key),
    ADD CONSTRAINT fk_episode_source_asset
        FOREIGN KEY (project_id, id, source_asset_id)
        REFERENCES asset (project_id, episode_id, id)
        ON DELETE RESTRICT;
