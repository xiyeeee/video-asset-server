# 人物素材提取 API

剧集上传完成后，可以在剧集详情或素材库显示「提取人物素材」。后端按配置抽帧、检测人物、分割背景，并产出透明 PNG。一次检测对应一条素材，同一帧可以有多条素材。`frameCharacterNumber` 仅在同一任务的同一来源帧内有效。前端以返回的素材 `id` 为列表键，当前接口按检测结果展示素材，跨帧角色身份尚未关联。

## 网关、鉴权和入口

本地网关为 `http://127.0.0.1:18080`。路径前缀为 `/api/ai/v1/projects/{projectId}/episodes/{episodeId}`。
所有网关请求复用登录客户端，携带 `Authorization: Bearer <accessToken>`。Java 校验当前账号、项目归属和剧集的原视频关联。
项目、剧集、任务和素材 ID 均以字符串传递。

| 方法 | 路径后缀 | 参数和响应 |
| --- | --- | --- |
| POST | `/character-extraction/tasks` | 空请求体。首次提交或任务仍在处理时返回 `202`，已有终态时返回 `200`，`data` 为任务 |
| GET | `/character-extraction/tasks/latest` | `200`，`data` 为该集最新任务。首次提交前为 `null` |
| GET | `/character-extraction/tasks/{taskId}` | `200`，`data` 为指定业务任务 |
| POST | `/character-extraction/tasks/{taskId}/retry` | 空请求体。最新任务已失败时创建新任务，`data.id` 可能变化。进行中返回 `202`，终态返回 `200` |
| GET | `/character-assets?page=1&size=20` | `200`，`data={items,page,size,total}`。默认第 1 页，每页 20 条，最多 100 条 |

请求示例：

```http
POST /api/ai/v1/projects/41/episodes/135/character-extraction/tasks HTTP/1.1
Authorization: Bearer <accessToken>
```

Java 受理响应示例。响应结构沿用 `Result<T>`。

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "id": "792",
    "episodeId": "135",
    "status": "PENDING",
    "stage": null,
    "progress": {"frameCount": null, "detectedCharacterCount": null},
    "assetCount": null,
    "errorCode": null,
    "createdAt": "2026-11-20T06:00:00Z",
    "finishedAt": null
  }
}
```

| `status` | 含义和页面行为 |
| --- | --- |
| `PENDING` | Java 已受理，等待提交或等待 Python 执行。继续轮询 |
| `RUNNING` | Python 正在提取，或 Java 正在导入已生成的素材。继续轮询 |
| `SUCCEEDED` | 素材和任务状态已一并入库。停止轮询并刷新素材列表，`assetCount` 可以是 `0` |
| `FAILED` | 任务已结束。停止轮询，展示失败状态和重试操作，读取 `errorCode` |

`stage` 可能为 `null`、`EXTRACTING_FRAMES`（抽帧）、`DETECTING_CHARACTERS`（检测人物）、
`SEGMENTING_CHARACTERS`（人物分割）或 `WRITING_RESULTS`（发布及导入结果）。
计数就绪后才有值，页面可以展示阶段和已知数量。日期使用 ISO 8601 UTC。

### 状态恢复与重试

1. 进入页面时查 `latest`。未创建任务时允许提交。提交后缓存 `data.id` 和当前任务状态。
2. 仅在 `PENDING`、`RUNNING` 时每 2–3 秒查询指定任务。离开页面、切换剧集或进入终态时停止该轮询。重新进入时从 `latest` 恢复。查询网络失败时可退避后再查。
3. 普通提交按集数幂等。提交响应超时时，可以查 `latest` 或重复提交。用户主动重试时调用 `retry`，使用返回的任务 ID 继续查。对同一次失败的重试，无论并发点击或超时重发，都复用已建立的后续任务。
4. `SUCCEEDED` 且 `assetCount: 0` 显示「此视频未检测到人物」。此时素材页返回 `{"items":[],"page":1,"size":20,"total":0}`。

常见 HTTP 错误包括 `400 INVALID_REQUEST`（ID 或分页参数无效）、`401 UNAUTHENTICATED`（登录失效或账号不可用）、
`404 NOT_FOUND`（项目、剧集、原视频或任务不可访问），以及 `503 VIDEO_STORAGE_UNAVAILABLE`（签名失败）。
轮询返回 `200` 且 `data.status` 为 `FAILED` 时按任务错误码展示。

| 任务 `errorCode` | 含义 |
| --- | --- |
| `PYTHON_TASK_LOST`、`CHARACTER_TASK_TIMEOUT` | 上游任务经有限恢复仍不可见，或任务达到总处理期限，可重试 |
| `SOURCE_VIDEO_NOT_FOUND`、`SOURCE_VIDEO_REF_INVALID`、`SOURCE_SIZE_INVALID` | 原视频已不可用或超过 AI 处理上限 |
| `CHARACTER_MODELS_UNAVAILABLE`、`FRAME_EXTRACTION_FAILED`、`FRAME_EXTRACTION_TIMEOUT` | 模型无法加载或抽帧失败 |
| `CHARACTER_DETECTION_FAILED`、`CHARACTER_SEGMENTATION_FAILED`、`RESULT_WRITE_FAILED`、`TASK_PROCESSING_FAILED` | Python 明确报告处理失败 |
| `CHARACTER_MANIFEST_INVALID`、`PYTHON_PROTOCOL_INVALID`、`SHARED_INPUT_INVALID`、`TASK_CONFLICT` | 产物、协议或共享目录异常 |

其他失败码统一展示「人物提取失败」，保留任务 ID 以便排查。

## 素材列表和图片访问

`GET /character-assets` 响应示例：

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "items": [{
      "id": "813",
      "taskId": "792",
      "timestampMs": 1001,
      "sourceFrame": "frame-000002.jpg",
      "frameCharacterNumber": 1,
      "sizeBytes": 139842,
      "url": "http://127.0.0.1:9000/video-assets/...?X-Amz-Signature=...",
      "expiresInSeconds": 600
    }],
    "page": 1,
    "size": 20,
    "total": 6
  }
}
```

- `timestampMs` 是实际选中的帧距视频播放起点的整数毫秒。点击卡片跳转时设置 `video.currentTime = timestampMs / 1000`。同帧多人可共享时间戳。不同帧偶尔也可能落在同一整数毫秒。
- `url` 是私有 MinIO 对象的 10 分钟签名地址，可直接用于 `<img src>` 或在新标签页打开原图。图片请求由签名鉴权，沿用现有媒体预览方式。签名过期时重新请求当前页。签名地址仅用于即时预览，避免写入日志或持久化缓存。
- `id` 在再次查询和签名刷新后保持稳定。原始视频继续通过现有集数播放接口获取。

## 本地联调与部署

先启动 Auth 以应用 Flyway V6。现有 AI 账号对 `ai_task`、`asset`、`video_episode` 的授权覆盖此次变更。
AI 服务需读 `video-original` 并能读写 `video-assets` 中的人物素材对象，浏览器通过 `MINIO_PLAYBACK_ENDPOINT` 访问签名地址。

| Java 配置项 | 默认值 |
| --- | --- |
| `CHARACTER_PYTHON_URL` | `http://127.0.0.1:8000` |
| `CHARACTER_PYTHON_CONNECT_TIMEOUT` / `CHARACTER_PYTHON_READ_TIMEOUT` | `2s` / `5s` |
| `CHARACTER_INPUT_ROOT` / `CHARACTER_OUTPUT_ROOT` | 相对于 Java 工作目录的 `.local/character-input` / `.local/character-output` |
| `CHARACTER_FRAME_INTERVAL_SECONDS` | `2` 秒，任务创建时固化 |
| `CHARACTER_POLL_INTERVAL` / `CHARACTER_TASK_TIMEOUT` | `3s` / `30m`，总期限包括排队和导入 |

Python 需使用同一批共享文件，可以在不同进程中用不同的绝对挂载路径。共享输入由 Java 写、Python 读，输出由 Python 写、Java 读。
同一套目录只供一个业务环境使用。进程使用不同的系统用户时，为共享目录配置共同的访问组或等效 ACL，并验证双方实际读写权限。
Python 使用单个 worker，仅向 Java 开放内部 HTTP 端口。启动及权重细节见 Python 仓库中的
`docs/java-character-extraction-api.md`。

```bash
# 在 Python 仓库根目录运行，对应 Java 默认目录
CHARACTER_INPUT_ROOT="$PWD/../video-asset-server/.local/character-input" \
CHARACTER_OUTPUT_ROOT="$PWD/../video-asset-server/.local/character-output" \
uv run --extra characters uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1
```

Java 遇到提交超时先查原任务 ID。Python 明确返回 `TASK_NOT_FOUND` 时才沿用原任务参数重提，总计最多提交三次。
一旦取得成功清单引用，Java 先保存该引用，随后独立完成导入。重复导入复用对象键，数据库通过
`(ai_task_id, source_frame, frame_character_no)` 唯一约束，以及任务状态和素材同一事务提交来保证一致性。
正常导入和 Python 已确认失败后，Java 清理对应的共享输入。超时或任务丢失时保留源文件供排查，需要按运维策略清理。
总超时只结束 Java 任务状态，Python 仍可能完成已领取的工作。
Python 发布目录以及未绑定数据库记录的对象可在确认无活跃任务引用后清理。

网关请求摘要在 `GatewayApplication` 日志，Python 提交、轮询、结果导入摘要在 `AiApplication` 日志。
Java 日志中的上游 `requestId` 可关联 Python 日志。以下命令使用实际日志文件和标识。

```bash
rg -F 'traceId=<网关请求标识> ' gateway.log
rg -F 'taskId=792 ' ai.log
```
