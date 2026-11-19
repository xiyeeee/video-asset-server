ALTER TABLE sys_user
    ADD COLUMN email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN email_verified_at TIMESTAMP(6) NULL,
    ADD UNIQUE KEY uk_sys_user_email (email);

CREATE TABLE auth_email_challenge (
    email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purpose VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    challenge_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    code_mac BINARY(32) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'FAILED',
    expires_at TIMESTAMP(6) NULL,
    cooldown_until TIMESTAMP(6) NULL,
    failed_attempts INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (email, purpose)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
