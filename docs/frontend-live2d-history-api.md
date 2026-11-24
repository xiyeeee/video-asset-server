# Live2D 模型历史前端联调

## 鉴权与分页

所有业务请求走当前网关，使用 `Authorization: Bearer <accessToken>`。
以下路径以 `/api/projects/{projectId}/live2d` 为前缀。项目、角色、版本、来源素材 ID 均为十进制字符串。
分页默认 `page=1&size=20`，`size` 最大 100。列表按保存时间倒序，同一时间以稳定的版本顺序区分。

| 方法及相对路径 | 用途 |
| --- | --- |
| `GET /characters?page=1&size=20` | 列出当前项目已有可用模型的角色 |
| `GET /characters/{characterId}/versions?page=1&size=20` | 列出指定角色的已保存版本 |
| `GET /characters/{characterId}/versions/{versionId}` | 获取版本详情及新签发的模型入口 |
| `GET /characters/{characterId}/versions/{versionId}/download` | 下载该版本完整 ZIP |
| `POST /tasks/{taskId}/retry-save` | 仅重试保存已生成的模型包 |

## 查询角色及版本

角色列表返回 HTTP 200。

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "items": [{"characterId":"31","name":"许宁","avatarUrl":null,"latestVersionId":"419"}],
    "page":1,
    "size":20,
    "total":1
  }
}
```

角色历史返回 HTTP 200。首次使用且没有版本时，`items` 为 `[]`。

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "items": [
      {"versionId":"419","versionNumber":2,"modelName":"许宁","savedAt":"2026-11-24T07:00:00Z","status":"AVAILABLE","thumbnailUrl":null},
      {"versionId":"410","versionNumber":1,"modelName":"许宁","savedAt":"2026-11-24T06:00:00Z","status":"AVAILABLE","thumbnailUrl":null}
    ],
    "page":1,
    "size":20,
    "total":2
  }
}
```

`AVAILABLE` 表示发布时 ZIP、入口和依赖已通过检查并写入持久化存储。失败或等待保存的任务不会进入历史。头像和缩略图来自版本来源图，字段可为空。有效地址仅用于当次展示，不宜持久化。

## 预览或下载

`GET /api/projects/27/live2d/characters/31/versions/419` 的响应示例。

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "projectId":"27",
    "characterId":"31",
    "sourceAssetId":"392",
    "taskId":"5ccbb13c4521481ab0756b37fc309238",
    "version":{"versionId":"419","versionNumber":2,"modelName":"许宁","savedAt":"2026-11-24T07:00:00Z","status":"AVAILABLE","thumbnailUrl":null},
    "modelEntryUrl":"/api/projects/27/live2d/models/419/files/<ticket>/model.model3.json",
    "previewExpiresInSeconds":600,
    "downloadUrl":"/api/projects/27/live2d/characters/31/versions/419/download"
  }
}
```

相对依赖按入口 URL 解析。保留路径中的票据，GET 模型资源时使用票据即可。票据限定项目及模型并在 10 分钟后过期，届时重新查询版本详情。前端与网关跨域时以网关域名解析入口 URL。预览和下载无需先查询生成任务。

下载接口需带 Bearer，返回 HTTP 200、`application/zip` 和 `Content-Disposition: attachment; filename="live2d-419.zip"`。前端可用已有鉴权请求取 Blob 并保存。

## 从生成任务进入历史

在原任务提交请求内新增可选的 `characterId`。

```json
{"modelName":"许宁迭代版","uploadFileId":"392","characterId":"31"}
```

选择角色后可以使用同项目的另一张来源图片。省略 `characterId` 时，用同项目的同一来源素材归档到同一角色，首次成功保存后才建立角色记录。不同素材不会按名称合并。

原任务详情和 WebSocket 快照增加 `saveStatus`、`saveErrorCode`，兼容已有 `status`、`result.modelId`、`result.modelEntryUrl` 字段。下例为保存后的任务数据。

```json
{
  "taskId":"5ccbb13c4521481ab0756b37fc309238",
  "status":"SUCCEEDED",
  "stage":"EXPORTING",
  "saveStatus":"SAVED",
  "saveErrorCode":null,
  "result":{
    "modelId":"419",
    "modelEntryUrl":"/api/projects/27/live2d/models/419/files/<ticket>/model.model3.json",
    "characterId":"31",
    "versionId":"419"
  },
  "error":null
}
```

- `NOT_STARTED` 表示后端还没收到生成产物。生成失败时看原有 `error`。
- `SAVING` 表示后端正在保存或补录。此时继续等待任务状态更新。
- `SAVED` 表示可用 `result.characterId` 和 `result.versionId` 导航到详情。
- `FAILED` 表示保存失败或旧产物无法补录。详情看 `saveErrorCode`。

`LIVE2D_SAVE_TIMEOUT` 或 `ARTIFACT_MISSING` 且任务已有生成产物时，可 POST `/tasks/{taskId}/retry-save`。HTTP 202 沿用任务响应包装及原任务 ID。重试优先复用原产物或已写入 MinIO 的 ZIP，继续用原任务查询。不可重试时返回 HTTP 409 `LIVE2D_SAVE_NOT_RETRYABLE`。损坏产物 `ARTIFACT_INVALID` 需要重新生成。

无登录凭证返回 401，资源不存在、归属不匹配及无效预览票据返回 404，路径或分页参数无效返回 400。存储暂不可用时可能返回 503 `VIDEO_STORAGE_UNAVAILABLE`。

旧任务只按项目内同一来源素材补录，保留原 ZIP 登记时间。缺失产物和来源无法确认分别记录 `ARTIFACT_MISSING`、`HISTORY_SOURCE_UNAVAILABLE`。`HISTORY_CHARACTER_UNAVAILABLE` 表示关联角色已不可用。
