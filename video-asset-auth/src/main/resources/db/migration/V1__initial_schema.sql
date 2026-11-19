CREATE TABLE sys_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    name VARCHAR(150) NOT NULL,
    description TEXT,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_project_owner_deleted (owner_user_id, deleted),
    CONSTRAINT fk_project_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (id)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE video_episode (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    episode_no INT UNSIGNED NOT NULL,
    title VARCHAR(150),
    duration_ms BIGINT UNSIGNED,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_episode_project_no (project_id, episode_no),
    UNIQUE KEY uk_episode_project_id (project_id, id),
    CONSTRAINT ck_episode_no CHECK (episode_no > 0),
    CONSTRAINT fk_episode_project FOREIGN KEY (project_id) REFERENCES project (id)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    episode_id BIGINT NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
    error_code VARCHAR(80),
    started_at TIMESTAMP(6) NULL,
    finished_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_task_request (episode_id, task_type, idempotency_key),
    KEY idx_ai_task_status_created (status, created_at),
    CONSTRAINT fk_ai_task_episode FOREIGN KEY (episode_id) REFERENCES video_episode (id)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE asset (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    episode_id BIGINT,
    asset_type VARCHAR(32) NOT NULL,
    bucket VARCHAR(63) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARBINARY(1024) NOT NULL,
    content_type VARCHAR(127),
    size_bytes BIGINT UNSIGNED,
    start_ms BIGINT UNSIGNED,
    end_ms BIGINT UNSIGNED,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_asset_object (bucket, object_key),
    KEY idx_asset_project_episode_type (project_id, episode_id, asset_type, deleted),
    CONSTRAINT ck_asset_interval CHECK (end_ms IS NULL OR (start_ms IS NOT NULL AND end_ms >= start_ms)),
    CONSTRAINT fk_asset_project FOREIGN KEY (project_id) REFERENCES project (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_asset_episode FOREIGN KEY (project_id, episode_id) REFERENCES video_episode (project_id, id)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `character` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    name VARCHAR(150) NOT NULL,
    description TEXT,
    appearance JSON,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_character_project_id (project_id, id),
    KEY idx_character_project_name (project_id, name, deleted),
    CONSTRAINT fk_character_project FOREIGN KEY (project_id) REFERENCES project (id)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dialogue (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    episode_id BIGINT NOT NULL,
    character_id BIGINT,
    line_text TEXT NOT NULL,
    start_ms BIGINT UNSIGNED NOT NULL,
    end_ms BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_dialogue_timeline (project_id, episode_id, start_ms, id),
    KEY idx_dialogue_speaker (project_id, character_id),
    CONSTRAINT ck_dialogue_interval CHECK (end_ms >= start_ms),
    CONSTRAINT fk_dialogue_episode FOREIGN KEY (project_id, episode_id) REFERENCES video_episode (project_id, id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_dialogue_character FOREIGN KEY (project_id, character_id) REFERENCES `character` (project_id, id)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE scene (
    id BIGINT NOT NULL AUTO_INCREMENT,
    episode_id BIGINT NOT NULL,
    scene_no INT UNSIGNED NOT NULL,
    start_ms BIGINT UNSIGNED NOT NULL,
    end_ms BIGINT UNSIGNED NOT NULL,
    description TEXT,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_scene_episode_no (episode_id, scene_no),
    KEY idx_scene_timeline (episode_id, start_ms, id),
    CONSTRAINT ck_scene_no CHECK (scene_no > 0),
    CONSTRAINT ck_scene_interval CHECK (end_ms >= start_ms),
    CONSTRAINT fk_scene_episode FOREIGN KEY (episode_id) REFERENCES video_episode (id)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
