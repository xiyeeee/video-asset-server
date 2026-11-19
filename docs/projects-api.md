# 项目管理 API

## 表结构与部署

项目管理由 Media 服务负责，使用 Flyway V1 已创建的 `project` 表。Flyway V3 添加索引
`idx_project_owner_updated (owner_user_id, deleted, updated_at DESC, id DESC)`，用于个人项目列表的稳定分页。Auth 仍负责执行迁移，Media 使用独立运行账号。

| 字段 | MySQL 类型 | 用途 |
| --- | --- | --- |
| `id` | `BIGINT` | 自增主键，接口使用十进制字符串 |
| `owner_user_id` | `BIGINT` | 指向 `sys_user.id`，由 Auth 签发的身份确定 |
| `name` | `VARCHAR(150)` | 项目名称，接口接受去掉首尾空白后 2–30 个字符 |
| `description` | `TEXT` | 项目简介，接口接受去掉首尾空白后 4–200 个字符，历史空值输出空字符串 |
| `deleted` | `TINYINT(1)` | 已软删除的项目不出现在列表和详情中 |
| `created_at`、`updated_at` | `TIMESTAMP(6)` | UTC 审计时间，由数据库维护 |

先让 Auth 应用 Flyway 迁移。已有数据库和全新环境均由管理员为 Media 建立独立数据库账号。下面使用默认账号名，密码需替换为本机或部署环境注入的同一份密码。

```sql
CREATE USER IF NOT EXISTS 'video_asset_media'@'%' IDENTIFIED BY '<MYSQL_MEDIA_PASSWORD>';
GRANT SELECT, INSERT, UPDATE ON video_asset.project TO 'video_asset_media'@'%';
GRANT SELECT (id, status, deleted) ON video_asset.sys_user TO 'video_asset_media'@'%';
```

Media 读取 `MYSQL_MEDIA_USERNAME`（默认 `video_asset_media`）和 `MYSQL_MEDIA_PASSWORD`。
开发环境默认使用 `MYSQL_PORT` 连接本机 `video_asset`。自定义 URL 使用
`MYSQL_MEDIA_JDBC_URL`，也支持共用的 `MYSQL_JDBC_URL`。

## 接口

本地 Gateway 地址为 `http://127.0.0.1:18080`。以下请求统一带上登录获得的
`Authorization: Bearer <accessToken>`。所有成功响应使用 `{"code":"OK","message":"Success","data":...}`。

| 方法 | Gateway 路径 | 请求 | 成功响应 |
| --- | --- | --- | --- |
| GET | `/api/media/v1/projects` | 可选 `q`、`page`、`size` | `200`，分页数据 |
| POST | `/api/media/v1/projects` | JSON `name`、`description`，两者必填 | `201`，项目详情 |
| GET | `/api/media/v1/projects/{projectId}` | 正整数格式的项目 ID | `200`，项目详情 |
| PATCH | `/api/media/v1/projects/{projectId}` | JSON `name`、`description`，至少一个非空值 | `200`，更新后的项目详情 |

项目 ID 在前端保持字符串。创建允许同名项目。PATCH 中缺省或 `null` 的字段保持原值，全部为缺省或 `null` 时返回 `400`。写入字段不接受空字符串。

列表默认 `page=1`、`size=20`，`size` 上限为 100。按 `updatedAt` 降序、ID 降序排列。`q` 最长 100 个字符，去除首尾空白后按名称和简介包含匹配，`%`、`_` 按普通字符匹配。无结果时 `items` 为空数组。

创建项目请求示例

```json
{"name":"反派拿捏","description":"管理漫剧短片及其待提取素材"}
```

项目详情和写入响应中的 `data` 示例

```json
{
  "id": "42",
  "name": "反派拿捏",
  "description": "管理漫剧短片及其待提取素材",
  "createdAt": "2026-11-19T07:00:00Z",
  "updatedAt": "2026-11-19T07:00:00Z"
}
```

列表返回的 `data` 结构为 `{"items":[...],"page":1,"size":20,"total":1}`，
`items` 中每项与详情响应的字段相同。`total` 是当前用户符合搜索条件的项目数。

| HTTP | 业务码 | 含义 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | 名称、简介、路径 ID、搜索词或分页参数无效 |
| `401` | `UNAUTHENTICATED` | Token 缺失、无效，或用户不存在、禁用、已删除 |
| `404` | `NOT_FOUND` | 项目不存在、已删除或不属于当前用户 |
| `500` | `INTERNAL_ERROR` | 非预期服务异常 |

当前阶段提供项目信息管理。项目 VO 的字段以本文为准，封面、集数、素材统计、删除和上传另有后续接口。Gateway 的运行日志可按 `route=media` 和 `traceId` 查找请求摘要。
