# 数据库与迁移

MySQL 8.4 中业务 schema 为 `video_asset`，默认编码是 `utf8mb4`，排序规则为 `utf8mb4_0900_ai_ci`。空卷初始化仅创建数据库、账号及 Nacos 表，Auth 的 Flyway 按版本执行 `db/migration/` 下的迁移。Auth 的运行账号权限见 [认证接口](auth-api.md)，Media 的运行账号和项目表索引见 [项目管理 API](projects-api.md)。迁移账号 `video_asset_migration` 只管理业务 schema。生产运行时以 `MYSQL_JDBC_URL`、`MYSQL_AUTH_USERNAME`、`MYSQL_AUTH_PASSWORD` 配置连接。建议部署时让一个 Auth 实例以迁移账号执行 Flyway，再令普通副本使用 `FLYWAY_ENABLED=false`。生产 JDBC 连接由部署方配置 TLS 和 UTC 会话。

| 表 | 用途与主要约束 |
| --- | --- |
| `sys_user` | 用户名唯一，存储密码摘要，支持逻辑删除 |
| `project` | 项目归属用户，按所有者与删除状态索引 |
| `video_episode` | 同一项目内集号唯一，存储时长和状态 |
| `ai_task` | 归属集，按状态与创建时间索引，集、任务类型及幂等键联合唯一 |
| `asset` | 可归属项目或集，Bucket 和按字节比较的对象 key 联合唯一，可保存时间范围 |
| `character` | 项目级角色，外观描述可存 JSON |
| `dialogue` | 集级台词及毫秒起止时间，说话角色可缺省，支持时间轴查询 |
| `scene` | 同一集的场景号唯一，支持时间轴查询 |

通过 [集数 API](episodes-api.md) 查看 V4 原视频关联、幂等字段和 Media 表权限。

V5 为 `ai_task` 增加投递时间、投递次数、执行期限和结果素材 ID。首帧分镜复用 `asset`，AI 运行账号授权见 [首帧解析 API](analysis-api.md)。

V6 在 `ai_task` 保存人物提取的视频版本、Python 阶段和结果清单引用。透明 PNG 存为 `asset`，通过任务外键与帧内编号关联。
`(ai_task_id, source_frame, frame_character_no)` 保证同一任务中同一帧的每个检测结果只入库一次。参见 [人物素材提取 API](frontend-character-extraction-api.md)。

外键使用 `ON DELETE RESTRICT`。素材和对话使用复合外键确保集、角色和项目归属一致。审计时间使用 UTC 会话下的 `TIMESTAMP(6)`，由数据库设置创建及更新时间，接口层使用 Java `Instant` 并输出 ISO 8601 UTC 时间。媒体偏移量使用从集起点计算的非负整数毫秒。

MyBatis-Plus 已启用 Mapper 包扫描和 MySQL 分页，上限 100。逻辑删除值为 0 和 1，后续仅在 `sys_user`、`project`、`asset`、`character` 的实体标记 `@TableLogic`。逻辑删除保留唯一键，用户名和对象 key 的复用需由后续业务显式定义。

```bash
docker compose exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot video_asset -e "SHOW TABLES; SELECT version, description, success FROM flyway_schema_history;"'
```

Nacos 原版初始化脚本单独放在 `sql/nacos/`，由 MySQL 容器在空卷上应用。[Nacos 3.0.3 MySQL Schema](https://github.com/alibaba/nacos/blob/3.0.3/distribution/conf/mysql-schema.sql)

## V7 上传自动提取

V7 为 `video_episode` 增加可空的 `upload_options`，比较上传的集号、文件和自动提取参数时沿用已有幂等键。
主任务表 `ai_extraction_job` 记录同源版本、投递时间、投递次数和期限。
`ai_extraction_step` 按任务和提取类型唯一，关联既有 `ai_task`。
`ai_extraction_job_state` 从真实步骤状态生成查询结果。历史剧集、首帧和人物素材可继续读取。
音轨使用 `asset_type='AUDIO_TRACK'`，对象放在私有 `video-audio` 桶，并保留执行任务关联及音频时长。

让 Auth 先应用 V7。对独立的 Media 和 AI 运行账号补齐以下权限，账号名按部署配置调整。
媒体签名端点须能访问 `video-assets`、`video-audio` 和历史 `video-frames` 所用的对象。

```sql
GRANT SELECT, INSERT, UPDATE ON video_asset.ai_extraction_job TO 'video_asset_media'@'%';
GRANT SELECT, INSERT, UPDATE ON video_asset.ai_extraction_step TO 'video_asset_media'@'%';
GRANT SELECT ON video_asset.ai_extraction_job_state TO 'video_asset_media'@'%';
GRANT SELECT ON video_asset.ai_task TO 'video_asset_media'@'%';
GRANT SELECT, INSERT, UPDATE ON video_asset.ai_extraction_job TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT, UPDATE ON video_asset.ai_extraction_step TO 'video_asset_ai'@'%';
GRANT SELECT ON video_asset.ai_extraction_job_state TO 'video_asset_ai'@'%';
```

[状态合成、恢复与结果版本](ai-task-design.md)

## V8 正式分镜

正式分镜在 `asset` 中沿用 `STORYBOARD`。`start_ms`、`end_ms` 记录镜头区间，
`shot_index`、`preview_timestamp_ms` 保存镜头索引和实际帧时间。素材 ID 同时作为对外的镜头 ID。
`(ai_task_id, shot_index)` 在同一执行版本中唯一。V8 新列可空，原有首帧继续兼容。
分镜对象存于私有 `video-frames`，现有 AI 和 Media 账号的表权限覆盖 V8。

## V9 对白提取

对白复用 `dialogue` 表。V9 新增 `ai_task_id`、`segment_index` 和 `speaker_id`，历史列保持兼容。
`(ai_task_id, segment_index)` 唯一，角色关联继续使用原 `character_id`，自动转写阶段该字段为空。
零句版本通过成功的 `ai_task` 表示。查询使用此版本状态，无需写占位对白。

```sql
GRANT SELECT, INSERT ON video_asset.dialogue TO 'video_asset_ai'@'%';
GRANT SELECT ON video_asset.dialogue TO 'video_asset_media'@'%';
```

## V10 背景候选图

`ai_task.input_shot_task_id` 保存背景在首次提交时使用的正式分镜版本。历史执行不需回填。
背景 JPEG 存为 `asset_type='BACKGROUND_IMAGE'`，位于私有 `video-assets` 桶。
`start_ms` 和 `end_ms` 是来源镜头区间，新列记录镜头索引、实际选帧时间、可用的镜头关联及检测状态。
`(ai_task_id, background_shot_index)` 唯一。V10 沿用现有 Media 和 AI 表权限。
