ALTER TABLE asset
    ADD COLUMN shot_index INT UNSIGNED NULL,
    ADD COLUMN preview_timestamp_ms BIGINT UNSIGNED NULL,
    ADD UNIQUE KEY uk_asset_task_shot (ai_task_id, shot_index),
    ADD CONSTRAINT ck_asset_shot_metadata CHECK (
        (shot_index IS NULL AND preview_timestamp_ms IS NULL)
        OR (
            asset_type = 'STORYBOARD' AND ai_task_id IS NOT NULL AND shot_index IS NOT NULL AND shot_index > 0
            AND start_ms IS NOT NULL AND end_ms IS NOT NULL AND preview_timestamp_ms IS NOT NULL
            AND start_ms <= preview_timestamp_ms AND preview_timestamp_ms < end_ms
        )
    );
