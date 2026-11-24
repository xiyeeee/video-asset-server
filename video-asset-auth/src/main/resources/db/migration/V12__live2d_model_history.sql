ALTER TABLE `character`
    ADD COLUMN live2d_version BIGINT UNSIGNED NOT NULL DEFAULT 0;

ALTER TABLE live2d_task
    ADD COLUMN character_id BIGINT NULL,
    ADD COLUMN history_issue VARCHAR(80) NULL,
    ADD CONSTRAINT fk_live2d_task_character FOREIGN KEY (project_id, character_id)
        REFERENCES `character` (project_id, id);

CREATE TABLE live2d_character_source (
    project_id BIGINT NOT NULL,
    source_asset_id BIGINT NOT NULL,
    character_id BIGINT NULL,
    PRIMARY KEY (project_id, source_asset_id),
    CONSTRAINT fk_live2d_character_source_asset FOREIGN KEY (source_asset_id) REFERENCES asset(id),
    CONSTRAINT fk_live2d_character_source_role FOREIGN KEY (project_id, character_id)
        REFERENCES `character` (project_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE live2d_model_version (
    id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    version_number BIGINT UNSIGNED NOT NULL,
    task_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_asset_id BIGINT NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    manifest_path VARCHAR(512) NOT NULL,
    saved_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_live2d_version_task (task_id),
    UNIQUE KEY uk_live2d_character_version (project_id, character_id, version_number),
    KEY idx_live2d_version_history (project_id, character_id, saved_at, id),
    CONSTRAINT fk_live2d_version_package FOREIGN KEY (id) REFERENCES asset(id),
    CONSTRAINT fk_live2d_version_character FOREIGN KEY (project_id, character_id)
        REFERENCES `character` (project_id, id),
    CONSTRAINT fk_live2d_version_source FOREIGN KEY (source_asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
