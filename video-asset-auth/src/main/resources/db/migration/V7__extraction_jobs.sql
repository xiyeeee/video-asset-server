ALTER TABLE video_episode ADD COLUMN upload_options VARCHAR(128) NULL;

CREATE TABLE ai_extraction_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    episode_id BIGINT NOT NULL,
    source_asset_id BIGINT NOT NULL,
    request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    type_signature VARCHAR(100) NOT NULL,
    regenerate BOOLEAN NOT NULL DEFAULT FALSE,
    dispatch_after TIMESTAMP(6) NULL,
    dispatch_deadline TIMESTAMP(6) NULL,
    dispatch_attempts INT UNSIGNED NOT NULL DEFAULT 0,
    dispatched_at TIMESTAMP(6) NULL,
    dispatch_failed_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_extraction_job_request (episode_id, source_asset_id, request_key),
    KEY idx_extraction_job_project (project_id, id),
    KEY idx_extraction_job_dispatch (dispatch_after, id),
    CONSTRAINT fk_extraction_job_episode FOREIGN KEY (project_id, episode_id)
        REFERENCES video_episode (project_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_extraction_job_source FOREIGN KEY (source_asset_id)
        REFERENCES asset (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_extraction_step (
    job_id BIGINT NOT NULL,
    extract_type VARCHAR(24) NOT NULL,
    worker_task_id BIGINT NULL,
    retry_from_worker_id BIGINT NULL,
    PRIMARY KEY (job_id, extract_type),
    KEY idx_extraction_step_worker (worker_task_id),
    CONSTRAINT fk_extraction_step_job FOREIGN KEY (job_id) REFERENCES ai_extraction_job (id) ON DELETE RESTRICT,
    CONSTRAINT fk_extraction_step_retry FOREIGN KEY (retry_from_worker_id) REFERENCES ai_task (id) ON DELETE RESTRICT,
    CONSTRAINT fk_extraction_step_worker FOREIGN KEY (worker_task_id) REFERENCES ai_task (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE SQL SECURITY INVOKER VIEW ai_extraction_job_state AS
SELECT j.id, j.project_id, j.episode_id, j.created_at,
       CASE
           WHEN totals.active_count > 0 AND totals.started_count > 0 THEN 'RUNNING'
           WHEN totals.active_count > 0 THEN 'PENDING'
           WHEN totals.success_count = totals.step_count THEN 'SUCCESS'
           WHEN totals.success_count > 0 THEN 'PARTIAL_SUCCESS'
           ELSE 'FAILED'
       END AS status,
       GREATEST(j.created_at, totals.first_started_at) AS started_at,
       CASE WHEN totals.active_count = 0 THEN
           GREATEST(j.created_at, COALESCE(totals.last_finished_at, j.dispatch_failed_at),
                    COALESCE(j.dispatch_failed_at, j.created_at))
       END AS finished_at
FROM ai_extraction_job j
JOIN (
    SELECT s.job_id, COUNT(*) AS step_count,
           SUM(IF(t.status = 'SUCCEEDED', 1, 0)) AS success_count,
           SUM(IF(t.status IN ('RUNNING', 'SUCCEEDED', 'FAILED'), 1, 0)) AS started_count,
           SUM(IF(t.status IN ('PENDING', 'RUNNING')
               OR (s.worker_task_id IS NULL AND p.dispatch_failed_at IS NULL), 1, 0)) AS active_count,
           MIN(t.started_at) AS first_started_at, MAX(t.finished_at) AS last_finished_at
    FROM ai_extraction_step s
    JOIN ai_extraction_job p ON p.id = s.job_id
    LEFT JOIN ai_task t ON t.id = s.worker_task_id
    GROUP BY s.job_id
) totals ON totals.job_id = j.id;
