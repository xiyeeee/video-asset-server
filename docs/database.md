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

## V11 Live2D 图片转模型

`live2d_task` 保存项目级任务、输入图片、轮询进度和模型资产 ID。
`live2d_model_file` 按模型资产 ID 和包内路径登记可加载的模型资源。
上传图片和模型 ZIP 分别使用 `LIVE2D_SOURCE_IMAGE` 和 `LIVE2D_MODEL` 素材类型，存入私有 `video-assets` 桶。
历史项目、剧集和人物素材无需回填。

让 Auth 先应用 V11，再为 Java AI 运行账号授权并启动新版 AI 和网关。

```sql
GRANT SELECT, INSERT, UPDATE ON video_asset.live2d_task TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT ON video_asset.live2d_model_file TO 'video_asset_ai'@'%';
```

## V12 Live2D 模型历史

`live2d_model_version` 将已保存的 ZIP 素材关联到项目、角色、源图片和任务。
同一任务只登记一次，每个角色的版本号由 `character.live2d_version` 在事务内递增。
`live2d_character_source` 为没有传角色 ID 的旧任务和客户端保留项目内的素材归属。
旧版本补录检查 MinIO 中的 ZIP、入口和依赖，未补录原因记录在 `live2d_task.history_issue`。

先由 Auth 执行 Flyway，再向 AI 运行账号补齐权限。模型包仍使用私有 `video-assets` 桶。

```sql
GRANT SELECT, INSERT ON video_asset.`character` TO 'video_asset_ai'@'%';
GRANT UPDATE (live2d_version, name, deleted) ON video_asset.`character` TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT, UPDATE ON video_asset.live2d_character_source TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT ON video_asset.live2d_model_version TO 'video_asset_ai'@'%';
```

其中 `name` 用于角色重命名，`deleted` 用于角色软删除。部署到已有数据库时需以 DBA/迁移账号执行此授权；AI 运行账号本身不应持有授权其他账号的权限。

## V13 Live2D 组合动作

`live2d_combo_compilation` 记录编译输入快照、诊断、短时预览产物引用，保存前以 SHA-256 对齐时间轴和模型版本。
`live2d_combo_motion` 保存可编辑时间轴、编译后的 Cubism `.motion3.json` 对象键和哈希；`live2d_combo_save_request` 记录保存状态和幂等键，失败请求保留用于同 requestId 重试。
编译动作文件放在 `video-assets` 私有桶；音轨继续引用 `video-audio` 中现有 `AUDIO_TRACK`，不复制素材。

让 Auth 执行 V13 后，为 AI 运行账号授权：

```sql
GRANT SELECT, INSERT, UPDATE ON video_asset.live2d_combo_compilation TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT, UPDATE ON video_asset.live2d_combo_motion TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT, UPDATE ON video_asset.live2d_combo_save_request TO 'video_asset_ai'@'%';
```

接口字段、内部 Python 编译契约和完整示例见 [Live2D 组合动作 API](live2d-combo-motion-api.md)。

## V14 Live2D 动作资产和能力映射

`live2d_motion_asset` 保存官方/项目动作目录，`live2d_motion_version` 区分草稿和不可变发布版本，`live2d_motion_reference` 固定组合片段引用的动作版本或音轨 asset。`live2d_model_parameter` 与 `live2d_model_capability_mapping` 按 MOC3 版本保存 Python 实测参数及用户候选/确认映射。当前幂等种子覆盖 Python 已实现的 blink、smile、nod 以及 speech handoff。

让 Auth 执行 V14 后，为 AI 运行账号授权：

```sql
GRANT SELECT, INSERT, UPDATE ON video_asset.live2d_motion_asset TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT, UPDATE ON video_asset.live2d_motion_version TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT, DELETE ON video_asset.live2d_motion_reference TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON video_asset.live2d_model_parameter TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON video_asset.live2d_model_capability_mapping TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON video_asset.live2d_model_analysis_failure TO 'video_asset_ai'@'%';
```

API 和剧情引用格式见 [Live2D 动作资产 API](live2d-motion-assets-api.md)。

## V14 Live2D 动作资产目录与模型兼容映射

`live2d_motion_asset` 保存官方基础动作和项目自定义动作/组合反应，`live2d_motion_version` 将草稿修订与不可变已发布版本分开。`live2d_motion_reference` 固定组合片段引用的动作版本或音轨素材 ID；发布版本不会跟随来源动作升级。
`live2d_model_parameter`、`live2d_model_capability_mapping` 按模型版本保存 Python Core 实测参数和候选/确认映射。V14 幂等种子使用 Python 当前可编译的 blink/smile/nod，以及 speech 音频交接定义。

先由 Auth 执行 V14，再为 AI 运行账号授权：

```sql
GRANT SELECT, INSERT, UPDATE ON video_asset.live2d_motion_asset TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT, UPDATE ON video_asset.live2d_motion_version TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT, DELETE ON video_asset.live2d_motion_reference TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON video_asset.live2d_model_parameter TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON video_asset.live2d_model_capability_mapping TO 'video_asset_ai'@'%';
```

动作目录、草稿/发布、版本兼容和剧情引用示例见 [Live2D 动作资产 API](live2d-motion-assets-api.md)。
