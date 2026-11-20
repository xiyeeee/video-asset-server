ALTER TABLE dialogue
    ADD COLUMN ai_task_id BIGINT NULL,
    ADD COLUMN segment_index INT UNSIGNED NULL,
    ADD COLUMN speaker_id VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uk_dialogue_task_segment (ai_task_id, segment_index),
    ADD CONSTRAINT fk_dialogue_ai_task FOREIGN KEY (ai_task_id) REFERENCES ai_task (id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_dialogue_extraction_segment CHECK (
        (ai_task_id IS NULL AND segment_index IS NULL AND speaker_id IS NULL)
        OR (ai_task_id IS NOT NULL AND segment_index IS NOT NULL AND segment_index > 0 AND end_ms > start_ms)
    );
