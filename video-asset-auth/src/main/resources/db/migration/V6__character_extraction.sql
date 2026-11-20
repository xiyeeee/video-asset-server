ALTER TABLE ai_task
    ADD COLUMN source_asset_id BIGINT NULL,
    ADD COLUMN python_interval_seconds DOUBLE NULL,
    ADD COLUMN python_stage VARCHAR(32) NULL,
    ADD COLUMN python_frame_count INT UNSIGNED NULL,
    ADD COLUMN python_detected_count INT UNSIGNED NULL,
    ADD COLUMN python_manifest_ref VARCHAR(512) NULL,
    ADD COLUMN python_asset_count INT UNSIGNED NULL,
    ADD CONSTRAINT fk_ai_task_character_source FOREIGN KEY (source_asset_id) REFERENCES asset (id)
        ON DELETE RESTRICT;

ALTER TABLE asset
    ADD COLUMN ai_task_id BIGINT NULL,
    ADD COLUMN source_frame VARCHAR(255) COLLATE utf8mb4_bin NULL,
    ADD COLUMN frame_character_no INT UNSIGNED NULL,
    ADD UNIQUE KEY uk_asset_task_character (ai_task_id, source_frame, frame_character_no),
    ADD CONSTRAINT fk_asset_ai_task FOREIGN KEY (ai_task_id) REFERENCES ai_task (id)
        ON DELETE RESTRICT;
