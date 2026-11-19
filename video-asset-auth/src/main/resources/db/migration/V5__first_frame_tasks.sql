ALTER TABLE ai_task
    ADD COLUMN dispatch_after TIMESTAMP(6) NULL,
    ADD COLUMN dispatch_attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN run_deadline TIMESTAMP(6) NULL,
    ADD COLUMN result_asset_id BIGINT NULL,
    ADD INDEX idx_ai_task_dispatch (task_type, status, dispatch_after, id),
    ADD INDEX idx_ai_task_deadline (task_type, status, run_deadline),
    ADD CONSTRAINT fk_ai_task_result_asset FOREIGN KEY (result_asset_id) REFERENCES asset (id)
        ON DELETE RESTRICT;
