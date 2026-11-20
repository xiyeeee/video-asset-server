ALTER TABLE ai_task
    ADD COLUMN input_shot_task_id BIGINT NULL,
    ADD CONSTRAINT fk_ai_task_input_shot FOREIGN KEY (input_shot_task_id) REFERENCES ai_task (id)
        ON DELETE RESTRICT;

ALTER TABLE asset
    ADD COLUMN background_shot_index INT UNSIGNED NULL,
    ADD COLUMN background_frame_ms BIGINT UNSIGNED NULL,
    ADD COLUMN background_shot_asset_id BIGINT NULL,
    ADD COLUMN background_method VARCHAR(24) NULL,
    ADD COLUMN background_person_status VARCHAR(16) NULL,
    ADD COLUMN background_subtitle_status VARCHAR(16) NULL,
    ADD COLUMN background_review_status VARCHAR(24) NULL,
    ADD UNIQUE KEY uk_asset_task_background (ai_task_id, background_shot_index),
    ADD CONSTRAINT fk_asset_background_shot FOREIGN KEY (background_shot_asset_id) REFERENCES asset (id)
        ON DELETE RESTRICT;
