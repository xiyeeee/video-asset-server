CREATE TABLE live2d_task (
    id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    project_id BIGINT NOT NULL,
    source_asset_id BIGINT NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    model_asset_id BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    stage VARCHAR(24) NULL,
    manifest_path VARCHAR(512) NULL,
    artifact_ref VARCHAR(512) NULL,
    error_code VARCHAR(80) NULL,
    error_stage VARCHAR(24) NULL,
    next_poll TIMESTAMP(6) NULL,
    deadline TIMESTAMP(6) NOT NULL,
    lease_version INT UNSIGNED NOT NULL DEFAULT 0,
    submission_count INT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_live2d_model (model_asset_id),
    KEY idx_live2d_poll (status, next_poll),
    CONSTRAINT fk_live2d_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_live2d_source FOREIGN KEY (source_asset_id) REFERENCES asset(id),
    CONSTRAINT fk_live2d_model FOREIGN KEY (model_asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE live2d_model_file (
    model_asset_id BIGINT NOT NULL,
    path VARCHAR(512) COLLATE utf8mb4_bin NOT NULL,
    content_type VARCHAR(80) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (model_asset_id, path),
    CONSTRAINT fk_live2d_model_file FOREIGN KEY (model_asset_id) REFERENCES asset(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
