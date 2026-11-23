# 单集上传、集数列表和原视频预览

## 表与存储

Auth 执行 Flyway V4。继续使用已有的 `video_episode` 和 `asset` 两张表。

| 新字段或约束 | 用途 |
| --- | --- |
| `video_episode.source_asset_id BIGINT NULL` | 当前原视频素材 ID，复合外键约束素材必须属于同一个项目和集数 |
| `video_episode.upload_key VARCHAR(100) NULL` | 上传幂等键，按项目唯一，大小写敏感 |
| `video_episode.upload_fingerprint BINARY(32) NULL` | 原文件名和文件内容的 SHA-256 指纹，核对重复请求 |
| `asset.original_filename VARCHAR(255) NULL` | 原视频显示文件名 |
| `asset(project_id,episode_id,id)` 唯一索引 | 支持原视频复合外键 |

已有集号唯一约束 `(project_id, episode_no)` 继续生效。上传先保留集数和对象键，MinIO 完成写入后，
在同一数据库事务中写入素材元数据并把集数状态改为 `PENDING`，表示原视频已保存。该流程允许已保留集号出现跳号。
列表只返回已绑定原视频的集数。既有记录的新增字段均可空，未绑定原视频时无需回填。

`asset` 记录 `ORIGINAL_VIDEO`、私有 Bucket `video-original`、内部对象键、`video/mp4`、文件字节数和原始文件名。
固定对象键为 `projects/{projectId}/episodes/{episodeId}/original.mp4`。上传失败或网络响应中断时，
用同一项目、幂等键和文件重试，继续使用原来保留的集数和对象键。相同请求并发时可能重复写同一份字节，
最终只绑定一条素材。对象写入成功但数据库提交未完成时，保留的集数仍关联可重试的固定对象键。
当前留存中断的上传预留记录，后续可按运营需要增加清理策略。

部署时先让 Auth 应用 V4，再补齐 Media 数据库账号的表级权限。以下为默认账号示例，保留项目接口原有的授权。

```sql
GRANT SELECT, INSERT, UPDATE ON video_asset.video_episode TO 'video_asset_media'@'%';
GRANT SELECT, INSERT ON video_asset.asset TO 'video_asset_media'@'%';
```

| 配置 | 默认值与含义 |
| --- | --- |
| `VIDEO_UPLOAD_MAX_FILE_SIZE` | `100MB`，即 100 MiB 的单文件限制 |
| `VIDEO_UPLOAD_MAX_REQUEST_SIZE` | `101MB`，含 multipart 开销 |
| `VIDEO_UPLOAD_GATEWAY_TIMEOUT_MS` | `300000`，仅上传路由的 Gateway 响应超时，单位毫秒 |
| `MINIO_PLAYBACK_ENDPOINT` | 缺省沿用 MinIO Endpoint。若服务走内网，配置为浏览器可达的 MinIO 域名，预签名 URL 使用此域名 |
| `MINIO_REGION` | `us-east-1`，如存储部署另有区域需保持一致 |

## Gateway 接口

本地 Gateway 为 `http://127.0.0.1:18080`。所有业务接口要求
`Authorization: Bearer <accessToken>`，成功响应为 `{"code":"OK","message":"Success","data":...}`。

| 方法 | 路径 | 请求 | 成功 |
| --- | --- | --- | --- |
| POST | `/api/media/v1/projects/{projectId}/episodes` | `multipart/form-data`，单个 `file` 字段；必填 `Idempotency-Key` 请求头 | 新绑定返回 `201`，幂等重试返回 `200`，`data` 为集数 |
| GET | `/api/media/v1/projects/{projectId}/episodes` | `page=1`、`size=20`，`size` 上限 100 | `200`，`data={items,page,size,total}` |
| GET | `/api/media/v1/projects/{projectId}/episodes/{episodeId}/playback` | 项目和集数 ID | `200`，`data={url,expiresInSeconds}` |

上传接受文件名以 `.mp4` 结尾、文件头符合支持的 MP4 容器品牌的单文件。该检查覆盖容器文件头，
视频编码兼容性由浏览器决定。上传接口自动分配项目内集号，以文件名去掉扩展名作为标题。
幂等键支持 1–100 位 ASCII 字母、数字、`.`、`_`、`:` 和 `-`，前端可使用 UUID。
在同一项目内用同一键重试必须上传字节和文件名相同的文件。

集数按 `episodeNumber` 升序。原有 `status` 描述上传记录，原视频保存后为 `PENDING`。
列表展示使用 `extractionStatus`，从当前原视频的任务记录实时计算。状态依次判断 `RUNNING`（解析中）、
`QUEUED`（排队中）、`SUCCEEDED`（至少一种提取成功）、`FAILED`（有失败且没有成功）和
`NOT_STARTED`（尚未提交）。历史成功结果也适用，具体素材类型的状态以任务中心为准。
ID 为十进制字符串，`durationMs` 为毫秒，尚未探测时返回 `null`。
`uploadedAt` 为 UTC 时间，`sizeBytes` 为文件字节数。

```json
{
  "id": "17",
  "projectId": "42",
  "episodeNumber": 1,
  "title": "第1集",
  "status": "PENDING",
  "extractionStatus": "NOT_STARTED",
  "originalFilename": "第1集.mp4",
  "contentType": "video/mp4",
  "sizeBytes": 1128375,
  "durationMs": null,
  "uploadedAt": "2026-11-19T09:00:00Z"
}
```

播放授权返回有效期 600 秒的 GET 签名地址，直接交给播放器，MinIO 支持 Range 请求。
播放器请求签名 URL 时无需附加业务 JWT。签名 URL 在过期前可直接访问，请勿写入日志或持久化缓存。
过期后重新请求 `/playback`。部署时让播放域对前端 Origin 允许 GET 和 Range 播放。
反向代理需保留签名时使用的 Host 和路径。

| HTTP | 业务码 | 处理 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | 校验路径、分页、文件字段和幂等键 |
| `400` | `INVALID_VIDEO` | 检查文件名或格式 |
| `401` | `UNAUTHENTICATED` | 恢复登录 |
| `404` | `NOT_FOUND` | 项目或集数不存在、不可访问或尚无可用原视频 |
| `409` | `VIDEO_UPLOAD_CONFLICT` | 同一键对应不同文件，更换文件时使用新键 |
| `413` | `VIDEO_TOO_LARGE` | 显示文件大小限制 |
| `503` | `VIDEO_STORAGE_UNAVAILABLE` | 保留文件和幂等键，允许重试 |
| `500` | `INTERNAL_ERROR` | 保存结果未知，使用原文件和幂等键重试 |

Gateway 的请求摘要可用 `route=media-episode-upload` 和 `traceId` 检索。Media 的存储异常日志可用
`episodeId` 检索，记录异常类型。原视频上传阶段还没有 AI 的 `taskId`。
