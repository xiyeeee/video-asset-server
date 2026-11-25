CREATE TABLE live2d_motion_asset (
    id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    project_id BIGINT NULL,
    code VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action_type VARCHAR(16) NOT NULL,
    category VARCHAR(48) NOT NULL,
    name VARCHAR(150) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    official TINYINT(1) NOT NULL DEFAULT 0,
    current_version_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    draft_version_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version_sequence BIGINT UNSIGNED NOT NULL DEFAULT 0,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_live2d_motion_code (code),
    KEY idx_live2d_motion_project (project_id, action_type, category, deleted, updated_at),
    CONSTRAINT fk_live2d_motion_project FOREIGN KEY (project_id) REFERENCES project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE live2d_motion_version (
    id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    motion_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_number BIGINT UNSIGNED NULL,
    status VARCHAR(16) NOT NULL,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
    definition_json JSON NOT NULL,
    definition_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_by BIGINT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_live2d_motion_version (motion_id, version_number),
    KEY idx_live2d_motion_version_history (motion_id, status, published_at, version_number),
    CONSTRAINT fk_live2d_motion_version_asset FOREIGN KEY (motion_id) REFERENCES live2d_motion_asset(id),
    CONSTRAINT fk_live2d_motion_version_author FOREIGN KEY (created_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE live2d_motion_asset
    ADD CONSTRAINT fk_live2d_motion_current_version FOREIGN KEY (current_version_id)
        REFERENCES live2d_motion_version(id),
    ADD CONSTRAINT fk_live2d_motion_draft_version FOREIGN KEY (draft_version_id)
        REFERENCES live2d_motion_version(id);

CREATE TABLE live2d_motion_reference (
    motion_version_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    clip_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reference_type VARCHAR(16) NOT NULL,
    source_motion_version_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_asset_id BIGINT NULL,
    replacement_motion_version_id CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    track_code VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    start_ms BIGINT UNSIGNED NOT NULL,
    duration_ms BIGINT UNSIGNED NOT NULL,
    source_start_ms BIGINT UNSIGNED NULL,
    PRIMARY KEY (motion_version_id, clip_id),
    CONSTRAINT fk_live2d_motion_ref_version FOREIGN KEY (motion_version_id)
        REFERENCES live2d_motion_version(id),
    CONSTRAINT fk_live2d_motion_ref_source FOREIGN KEY (source_motion_version_id)
        REFERENCES live2d_motion_version(id),
    CONSTRAINT fk_live2d_motion_ref_replacement FOREIGN KEY (replacement_motion_version_id)
        REFERENCES live2d_motion_version(id),
    CONSTRAINT fk_live2d_motion_ref_asset FOREIGN KEY (source_asset_id) REFERENCES asset(id),
    CONSTRAINT ck_live2d_motion_ref_source CHECK (
        (reference_type='MOTION_VERSION' AND source_motion_version_id IS NOT NULL AND source_asset_id IS NULL)
        OR (reference_type='AUDIO_ASSET' AND source_motion_version_id IS NULL AND source_asset_id IS NOT NULL
            AND replacement_motion_version_id IS NULL)
    ),
    CONSTRAINT ck_live2d_motion_ref_duration CHECK (duration_ms > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE live2d_model_capability_mapping (
    model_version_id BIGINT NOT NULL,
    capability_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mapping_status VARCHAR(16) NOT NULL,
    parameter_ids JSON NOT NULL,
    missing_parameter_ids JSON NOT NULL,
    evidence_json JSON NOT NULL,
    asset_refs_json JSON NOT NULL,
    part_ids_json JSON NOT NULL,
    reason VARCHAR(500) NOT NULL DEFAULT '',
    analysis_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source VARCHAR(16) NOT NULL,
    updated_by BIGINT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (model_version_id, capability_id),
    CONSTRAINT fk_live2d_capability_model FOREIGN KEY (model_version_id)
        REFERENCES live2d_model_version(id),
    CONSTRAINT fk_live2d_capability_author FOREIGN KEY (updated_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE live2d_model_parameter (
    model_version_id BIGINT NOT NULL,
    parameter_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    min_value DOUBLE NULL,
    max_value DOUBLE NULL,
    default_value DOUBLE NULL,
    active TINYINT(1) NULL,
    active_decreasing TINYINT(1) NULL,
    active_increasing TINYINT(1) NULL,
    PRIMARY KEY (model_version_id, parameter_id),
    CONSTRAINT fk_live2d_model_parameter_version FOREIGN KEY (model_version_id)
        REFERENCES live2d_model_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE live2d_model_analysis_failure (
    model_version_id BIGINT NOT NULL,
    error_code VARCHAR(80) NOT NULL,
    error_message VARCHAR(500) NOT NULL,
    retry_count INT UNSIGNED NOT NULL DEFAULT 1,
    failed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (model_version_id),
    CONSTRAINT fk_live2d_analysis_failure_model FOREIGN KEY (model_version_id)
        REFERENCES live2d_model_version(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO live2d_motion_asset
    (id,project_id,code,action_type,category,name,description,official,current_version_id,version_sequence)
VALUES
    ('00000000000000000000000000000001',NULL,'blink','BASIC','face','眨眼','沿模型实际眼睛开合绑定生成眨眼曲线',1,NULL,1),
    ('00000000000000000000000000000002',NULL,'smile','BASIC','face','微笑','控制模型实际嘴形绑定，不改变口型开合',1,NULL,1),
    ('00000000000000000000000000000003',NULL,'nod','BASIC','head','点头','沿模型实际头部俯仰绑定生成点头曲线',1,NULL,1),
    ('00000000000000000000000000000004',NULL,'speech','DYNAMIC','mouth','说话口型','由前端播放器按台词音频 RMS 驱动嘴巴开合',1,NULL,1)
ON DUPLICATE KEY UPDATE code=VALUES(code);

INSERT INTO live2d_motion_version
    (id,motion_id,version_number,status,definition_json,created_by,published_at)
VALUES
    ('10000000000000000000000000000001','00000000000000000000000000000001',1,'PUBLISHED',
     JSON_OBJECT('schemaVersion',1,'durationMs',1000,'requiredCapabilities',JSON_ARRAY('eyelid-left','eyelid-right'),
       'tracks',JSON_ARRAY(
         JSON_OBJECT('trackId','eyelid-left','semanticTrack','eyelid-left','capabilityId','eyelid-left',
           'direction','DECREASING','keyframes',JSON_ARRAY(JSON_OBJECT('timeMs',0,'value',0),
             JSON_OBJECT('timeMs',380,'value',1),JSON_OBJECT('timeMs',520,'value',1),JSON_OBJECT('timeMs',1000,'value',0))),
         JSON_OBJECT('trackId','eyelid-right','semanticTrack','eyelid-right','capabilityId','eyelid-right',
           'direction','DECREASING','keyframes',JSON_ARRAY(JSON_OBJECT('timeMs',0,'value',0),
             JSON_OBJECT('timeMs',380,'value',1),JSON_OBJECT('timeMs',520,'value',1),JSON_OBJECT('timeMs',1000,'value',0))))),NULL,UTC_TIMESTAMP(6)),
    ('10000000000000000000000000000002','00000000000000000000000000000002',1,'PUBLISHED',
      JSON_OBJECT('schemaVersion',1,'durationMs',1000,'requiredCapabilities',JSON_ARRAY('mouth-form'),
       'tracks',JSON_ARRAY(JSON_OBJECT('trackId','face','semanticTrack','mouth-form','capabilityId','mouth-form','direction','INCREASING',
         'keyframes',JSON_ARRAY(JSON_OBJECT('timeMs',0,'value',0),JSON_OBJECT('timeMs',280,'value',0.75),
           JSON_OBJECT('timeMs',750,'value',0.75),JSON_OBJECT('timeMs',1000,'value',0))))),NULL,UTC_TIMESTAMP(6)),
    ('10000000000000000000000000000003','00000000000000000000000000000003',1,'PUBLISHED',
      JSON_OBJECT('schemaVersion',1,'durationMs',1000,'requiredCapabilities',JSON_ARRAY('head-pitch'),
       'tracks',JSON_ARRAY(JSON_OBJECT('trackId','head','semanticTrack','head-pitch','capabilityId','head-pitch','direction','DECREASING',
         'keyframes',JSON_ARRAY(JSON_OBJECT('timeMs',0,'value',0),JSON_OBJECT('timeMs',400,'value',0.65),
           JSON_OBJECT('timeMs',560,'value',0.65),JSON_OBJECT('timeMs',1000,'value',0))))),NULL,UTC_TIMESTAMP(6)),
    ('10000000000000000000000000000004','00000000000000000000000000000004',1,'PUBLISHED',
      JSON_OBJECT('schemaVersion',1,'durationMs',120000,'requiredCapabilities',JSON_ARRAY('mouth-open'),
       'handoffBehavior','AUDIO_RMS_TO_MOUTH',
       'tracks',JSON_ARRAY(JSON_OBJECT('trackId','mouth','semanticTrack','mouth-open','capabilityId','mouth-open','direction','INCREASING',
         'keyframes',JSON_ARRAY()))),NULL,UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE id=VALUES(id);

UPDATE live2d_motion_asset SET current_version_id='10000000000000000000000000000001'
WHERE id='00000000000000000000000000000001';
UPDATE live2d_motion_asset SET current_version_id='10000000000000000000000000000002'
WHERE id='00000000000000000000000000000002';
UPDATE live2d_motion_asset SET current_version_id='10000000000000000000000000000003'
WHERE id='00000000000000000000000000000003';
UPDATE live2d_motion_asset SET current_version_id='10000000000000000000000000000004'
WHERE id='00000000000000000000000000000004';
