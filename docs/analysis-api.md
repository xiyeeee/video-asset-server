# 首帧解析 API

用户上传并绑定原视频后可以发起首帧解析。AI 服务经 RabbitMQ 异步读取原 MP4，使用 FFmpeg 生成一张 JPEG 分镜，成功后可在素材库预览。原视频和现有集数状态保持原有语义，解析进度以任务状态为准。

## Gateway 接口

本地 Gateway 为 `http://127.0.0.1:18080`。业务请求使用 `Authorization: Bearer <accessToken>`。
下表路径以 `/api/ai/v1/projects/{projectId}/episodes/{episodeId}` 为前缀。项目、集数、任务和素材 ID 在前端均作为字符串使用。

| 方法 | 路径后缀 | 请求体 | 成功响应 |
| --- | --- | --- | --- |
| POST | `/analysis` | 空 | `202` 为待处理或处理中，`200` 为已有终态，`data` 为任务 |
| GET | `/analysis` | 无 | `200`，`data` 为任务，首次提交前为 `null` |
| POST | `/analysis/{taskId}/retry` | 空 | `202` 为待处理或处理中，`200` 为已有终态，`data` 为原任务 ID 对应的任务 |
| GET | `/frames` | 无 | `200`，`data` 为分镜数组，暂无素材时为 `[]` |

`POST /analysis` 按集数幂等。双击、刷新及网络超时后重发均复用同一个任务。失败后使用重试接口，复用任务 ID。非失败任务调用重试接口会返回当前状态。
`202` 表示任务已受理。只有 `SUCCEEDED` 才表示分镜已写入并可查询。`attemptCount` 统计工作进程实际开始处理的次数，排队和消息重投不增加它。所有日期为 ISO 8601 UTC。

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "id": "792",
    "episodeId": "135",
    "status": "SUCCEEDED",
    "attemptCount": 1,
    "errorCode": null,
    "resultAssetId": "813",
    "createdAt": "2026-11-19T09:32:00Z",
    "startedAt": "2026-11-19T09:32:01Z",
    "finishedAt": "2026-11-19T09:32:02Z"
  }
}
```

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 等待开始，`startedAt`、`finishedAt`、`resultAssetId` 和 `errorCode` 均为空 |
| `RUNNING` | 工作进程已领取，`startedAt` 有值 |
| `SUCCEEDED` | 分镜可查询，`finishedAt` 和 `resultAssetId` 有值 |
| `FAILED` | 本次解析结束，`errorCode` 有值。队列投递失败时可能尚未开始 |

`GET /frames` 的 `data` 示例。首帧用 `timestampMs: 0` 表示视频起点，`sizeBytes` 单位为字节。

```json
[
  {
    "id": "813",
    "name": "首帧分镜",
    "type": "storyboard",
    "timestampMs": 0,
    "sizeBytes": 59234,
    "url": "http://localhost:9000/video-frames/...?...",
    "expiresInSeconds": 600
  }
]
```

将签名 `url` 直接用于 `<img src>`。刷新、重新进入页面或签名过期时重新调用 `GET /frames`。MinIO 图片请求使用签名鉴权。

## 错误处理

路径参数格式错误返回 `400`。登录凭证缺失、失效或账号不可用返回 `401`。项目、集数、原视频关联或指定任务不存在，以及资源归属不匹配时返回 `404 NOT_FOUND`。预览地址签发失败返回 `503 VIDEO_STORAGE_UNAVAILABLE`。
任务处理失败时，`GET /analysis` 仍返回 `200`，检查 `data.status` 和 `data.errorCode`。

| `data.errorCode` | 前端提示建议 |
| --- | --- |
| `QUEUE_UNAVAILABLE`、`FFMPEG_UNAVAILABLE`、`WORKSPACE_UNAVAILABLE` | 解析服务暂不可用，请稍后重试 |
| `SOURCE_NOT_FOUND`、`SOURCE_SIZE_INVALID` | 原视频不可用或超出解析上限，请检查上传文件 |
| `FRAME_PROCESS_FAILED` | 无法从该视频提取首帧，请检查视频能否正常播放 |
| `FRAME_PROCESS_TIMEOUT`、`TASK_TIMEOUT` | 解析超时，请重试 |
| `SOURCE_READ_FAILED`、`FRAME_STORE_FAILED`、`FRAME_PROCESS_INTERRUPTED`、`TASK_INTERNAL_ERROR` | 解析失败，请稍后重试 |

## 部署与检索

1. 由 Auth 应用 Flyway V5。为 AI 服务创建独立数据库账号并配置 `MYSQL_AI_USERNAME`、`MYSQL_AI_PASSWORD`。生产使用 `MYSQL_AI_JDBC_URL` 或共享的 `MYSQL_JDBC_URL`，连接配置复用项目现有规则。
2. AI 服务连接现有 RabbitMQ，使用 `video.asset.ai.task` 队列。为 MinIO 凭证授予 `video-original` 对象读取，以及 `video-frames` 对象读、写和删除权限。`MINIO_PLAYBACK_ENDPOINT` 需是浏览器可达的 MinIO 地址，部署了 HTTPS 的前端应使用 HTTPS 预览端点。
3. 在 AI 运行环境安装 FFmpeg，`AI_FFMPEG_PATH` 默认为 `ffmpeg`，也可设置绝对路径。`AI_MAX_SOURCE_SIZE` 默认沿用 `VIDEO_UPLOAD_MAX_FILE_SIZE`，后者未配置时为 100 MiB。单次 FFmpeg 执行上限为 45 秒，任务领取后执行期限为 5 分钟。

AI 数据库运行账号权限示例，先按部署环境创建账号并注入密码。

```sql
GRANT SELECT (id, status, deleted) ON video_asset.sys_user TO 'video_asset_ai'@'%';
GRANT SELECT ON video_asset.project TO 'video_asset_ai'@'%';
GRANT SELECT ON video_asset.video_episode TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT, UPDATE ON video_asset.ai_task TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT ON video_asset.asset TO 'video_asset_ai'@'%';
```

V5 复用 `ai_task` 和 `asset`。任务行保存待投递时间。RabbitMQ 未确认时短间隔重投，确认后仍未被领取的任务低频补投，工作进程按状态和尝试次数原子领取。图片上传成功后，在同一数据库事务中绑定素材并更新任务。过期执行不能覆盖下一次尝试；无法判定数据库提交结果时保留该次对象以供排查。

网关请求摘要在 `GatewayApplication` 控制台，任务事件在 `AiApplication` 控制台。将日志写入文件时，按实际文件路径和标识检索。

```bash
rg -F 'traceId=<网关请求标识> ' gateway.log
rg -F 'taskId=792 ' ai.log
```
