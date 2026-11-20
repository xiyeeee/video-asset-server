# 上传自动提取、任务中心和素材库接口

本地网关为 `http://127.0.0.1:18080`。所有下列业务请求携带 `Authorization: Bearer <accessToken>`。
资源接口校验账号和项目归属。ID 在响应中是字符串，避免 JavaScript 整数精度问题。
示例成功响应统一包装为 `{"code":"OK","message":"Success","data":...}`。错误响应的 `data` 为 `null`。

## 上传和能力

| 方法与路径 | 参数 | 成功状态 |
| --- | --- | --- |
| GET `/api/media/extraction-capabilities` | 无 | `200`，`data` 为所有类型及支持状态 |
| POST `/api/media/videos/upload` | multipart，见下文 | 新上传 `201`，幂等重放 `200` |

能力响应的 `data` 示例。

```json
[
  {"type":"SHOT","supported":false},
  {"type":"CHARACTER","supported":true},
  {"type":"DIALOGUE","supported":false},
  {"type":"BACKGROUND","supported":false},
  {"type":"AUDIO","supported":true},
  {"type":"STORY","supported":false}
]
```

当前 Python 支持人物透明图和原音轨。分镜能力枚举保留供后续接入，历史首帧在素材库仍可读取。
能力接口反映部署启用的处理协议。页面按该结果显示选项，默认关闭自动提取时只上传视频。

| multipart 字段 | 类型 | 说明 |
| --- | --- | --- |
| `file` | 一个 MP4 文件 | 默认上限 100 MiB，可由部署方调整 |
| `projectId` | 正整数 | 所属项目 |
| `episodeNumber` | 正整数 | 集号，同一项目内唯一 |
| `autoExtract` | 布尔值 | 缺省 `false` |
| `extractTypes` | 枚举列表 | 自动提取时必填。可传重复字段，或同一字段使用逗号分隔 |

每次用户发起上传时生成一个 `Idempotency-Key`，取值为 1～100 个 ASCII 字母、数字或 `._:-` 字符。
同一上传超时重发时复用原文件、项目、集号、提取类型和这个键。

```bash
curl -X POST http://127.0.0.1:18080/api/media/videos/upload \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Idempotency-Key: episode-8-upload-1' \
  -F 'file=@episode-8.mp4' -F 'projectId=41' -F 'episodeNumber=8' \
  -F 'autoExtract=true' -F 'extractTypes=CHARACTER,AUDIO'
```

自动提取受理后，上传直接返回任务 ID。Python 处理在后台执行。

```json
{
  "code":"OK",
  "message":"Success",
  "data":{
    "videoId":"813","episodeId":"135","autoExtract":true,
    "taskId":"792","taskStatus":"PENDING","taskErrorCode":null
  }
}
```

关闭自动提取时返回 `autoExtract:false`，`taskId`、`taskStatus`、`taskErrorCode` 均为 `null`。
视频已经保存但任务建单失败时仍返回视频和剧集 ID，`taskId:null`、`taskStatus:"FAILED"`、
`taskErrorCode:"EXTRACTION_TASK_CREATE_FAILED"`。可在任务中心用该剧集 ID 补建任务，也可用原上传参数与幂等键重发。

## 任务中心

| 方法与路径 | 参数和语义 |
| --- | --- |
| POST `/api/ai/tasks` | JSON 请求，包含 `episodeId` 和非空 `extractTypes`；可选请求头 `Idempotency-Key` 和请求体 `regenerate` |
| GET `/api/ai/tasks` | 可选 `projectId`、`episodeId`、`status`，按任务 ID 倒序；`data={items,pageNum,pageSize,total}` |
| GET `/api/ai/tasks/{taskId}` | 任务详情，包含步骤 |
| GET `/api/ai/tasks/{taskId}/steps` | 仅返回步骤数组 |
| POST `/api/ai/tasks/{taskId}/retry` | 空请求体。任务走完后只重试失败步骤，返回同一个主任务 ID |

```http
POST /api/ai/tasks
Content-Type: application/json
Authorization: Bearer <accessToken>

{"episodeId":"135","extractTypes":["AUDIO","CHARACTER"]}
```

没有指定幂等键时，同一剧集原视频、同一组类型复用同一个主任务，包括上传时创建的任务。类型顺序不影响去重。
显式幂等键可创建另一条任务记录。常规创建会复用可用的同源步骤。`regenerate:true` 须同时提供新的幂等键，会创建独立素材版本。
重用同一个键并改变类型或 `regenerate` 返回 `409 EXTRACTION_TASK_CONFLICT`。

创建与重试在任务待执行或执行中返回 `202`，复用终态时返回 `200`。读取接口返回 `200`。
列表条目包含任务字段，`steps` 为 `null`，详情和步骤接口提供完整步骤。

```json
{
  "taskId":"792",
  "projectId":"41",
  "episodeId":"135",
  "status":"PARTIAL_SUCCESS",
  "progress":null,
  "createdAt":"2026-11-20T07:00:00Z",
  "startedAt":"2026-11-20T07:00:02Z",
  "finishedAt":"2026-11-20T07:00:15Z",
  "steps":[
    {"type":"AUDIO","status":"FAILED","progress":null,"stage":"EXTRACTING_AUDIO",
     "assetCount":null,"errorCode":"AUDIO_STREAM_NOT_FOUND","sourceTaskId":"921"},
    {"type":"CHARACTER","status":"SUCCESS","progress":100,"stage":"WRITING_RESULTS",
     "assetCount":6,"errorCode":null,"sourceTaskId":"922"}
  ]
}
```

| 任务状态 | 含义 |
| --- | --- |
| `PENDING` | 等待投递或开始 |
| `RUNNING` | 已有步骤在处理或完成，仍有步骤未结束 |
| `SUCCESS` | 所有步骤成功，允许零素材 |
| `PARTIAL_SUCCESS` | 部分成功，成功素材已可用 |
| `FAILED` | 全部步骤失败或调度失败 |
| `CANCELLED` | 保留状态，供后续取消功能使用 |

前端仅在 `PENDING`、`RUNNING` 时每 2–3 秒轮询详情，在终态、离开页面或切换任务时停止。
步骤阶段是 Python 已报告的真实事件，阶段可能为 `null`。成功时进度为 100，当前协议其余情况返回 `null`。
`sourceTaskId` 是产出该步骤的执行任务 ID，与任务中心的主任务 ID 不同。
列表和素材页分页参数均从 1 开始，默认 `pageNum=1&pageSize=20`，单页最多 100 条。

## 素材库

| 方法与路径 | 参数和响应 |
| --- | --- |
| GET `/api/media/episodes/{episodeId}/summary` | `data={episodeId,summary}`，每项包含 `type,count,status` |
| GET `/api/media/episodes/{episodeId}/assets` | 必填 `type`，可选 `pageNum`、`pageSize`、`sourceTaskId`；`data={items,pageNum,pageSize,total,status}` |

摘要始终返回六种类型。数量来自素材表，按当前默认版本统计。未发起时为 `NOT_STARTED`，已接入能力按最近状态显示
`PENDING`、`RUNNING`、`SUCCESS` 或 `FAILED`。未支持类型为 `UNSUPPORTED`。
零素材也可以是成功。历史首帧可读，`SHOT` 仍标为 `UNSUPPORTED`，因为当前不能启动完整切镜。
若重新提取时失败，已发布成功版本仍可读。

```json
{"episodeId":"135","summary":[
  {"type":"SHOT","count":1,"status":"UNSUPPORTED"},
  {"type":"CHARACTER","count":6,"status":"SUCCESS"},
  {"type":"DIALOGUE","count":0,"status":"UNSUPPORTED"},
  {"type":"BACKGROUND","count":0,"status":"UNSUPPORTED"},
  {"type":"AUDIO","count":1,"status":"SUCCESS"},
  {"type":"STORY","count":0,"status":"UNSUPPORTED"}
]}
```

素材共同字段包含 `projectId`、`episodeId`、`assetId`、`assetType`、`createdAt`、
`sourceTaskId`、`sizeBytes`、`expiresInSeconds` 和类型特定的 `details`。

| 类型 | `details` 字段 |
| --- | --- |
| `CHARACTER` | `characterId,shotId,timestampMs,sourceFrame,frameCharacterNumber,imageUrl`。未归组的角色或分镜关联为 `null` |
| `AUDIO` | `audioType,startMs,endMs,audioUrl`，当前为 `ORIGINAL_TRACK` WAV |
| `SHOT` | 历史首帧 `shotId,startMs,endMs,frameUrl`，没有正式分镜关联时 `shotId` 和 `endMs` 为 `null` |
| `DIALOGUE`、`BACKGROUND`、`STORY` | 当前返回空列表和 `UNSUPPORTED` |

```json
{
  "items":[{
    "projectId":"41","episodeId":"135","assetId":"814","assetType":"CHARACTER",
    "createdAt":"2026-11-20T07:00:15Z","sourceTaskId":"922","sizeBytes":139842,
    "expiresInSeconds":600,
    "details":{"characterId":null,"shotId":null,"timestampMs":1001,
      "sourceFrame":"frame-000002.jpg","frameCharacterNumber":1,"imageUrl":"https://media.example/signed.png"}
  }],
  "pageNum":1,"pageSize":20,"total":6,"status":"SUCCESS"
}
```

全部时间偏移量为相对视频起点的整数毫秒，日期为 ISO 8601。签名链接通常有效 600 秒，可直接用于
`<img>`、`<audio>` 或浏览器访问，过期后重新取当前页。素材读取和任务触发分别使用上述接口。

## 错误码

| HTTP 或任务错误码 | 处理方式 |
| --- | --- |
| `400 INVALID_REQUEST` | 检查字段、页码和幂等键 |
| `400 INVALID_VIDEO` / `413 VIDEO_TOO_LARGE` | 检查 MP4 格式和文件大小 |
| `401 UNAUTHENTICATED` | 重新登录 |
| `404 NOT_FOUND` | 资源缺失或不属于当前账号 |
| `409 EPISODE_NUMBER_CONFLICT` | 同一项目的集号已被另一上传占用 |
| `409 VIDEO_UPLOAD_CONFLICT` / `409 EXTRACTION_TASK_CONFLICT` | 相同幂等键收到不一致参数 |
| `422 EXTRACTION_UNSUPPORTED` | 刷新能力并取消选择不支持类型 |
| `503 VIDEO_STORAGE_UNAVAILABLE` | 存储暂不可用，按原请求参数重试 |
| `EXTRACTION_TASK_CREATE_FAILED` | 上传成功、任务建单失败，通过任务中心补建 |
| `QUEUE_UNAVAILABLE`、`PYTHON_TASK_LOST`、`CHARACTER_TASK_TIMEOUT`、`EXTRACTION_TASK_TIMEOUT` | 任务达到恢复上限或期限，可发起重试 |
| `AUDIO_STREAM_NOT_FOUND` | 视频无音轨，已成功的人物素材可继续使用 |
| 其他步骤错误 | 显示步骤失败并保留任务 ID，后端按错误码排查 |
