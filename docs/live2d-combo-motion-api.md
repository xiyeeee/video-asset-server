# Live2D 组合动作 API（Java、Python 与前端）

本文件是三端共享契约。浏览器只调用 Java 鉴权 API。Java 校验项目、固定模型版本、动作/台词素材后调用 Python；Python 检查模型参数绑定，生成可被 Cubism 直接播放的组合 `.motion3.json`。Java 将该产物写入 MinIO 并保存可编辑时间轴，两者以同一内容摘要和模型版本关联。

可复用的基础动作、动作版本、模型能力映射和剧情版本引用约定见 [Live2D 动作资产 API](live2d-motion-assets-api.md)。

所有时间值为整数毫秒。`startTimeMs` 是片段在组合时间轴上的位置；对白的 `sourceStartMs` 是相对原音轨的裁剪起点。总时长最大 120000 ms、最多 32 段，每段至少 2 ms。项目、模型、素材 ID 为十进制字符串；requestId、compilationId、comboId、segmentId 是 32 位小写十六进制字符串。Java API 使用 `Authorization: Bearer <accessToken>`，只能访问当前用户拥有的项目。

## 1. 查询素材来源

先用现有接口查询角色和模型版本：

- `GET /api/projects/{projectId}/live2d/characters?page=1&size=20`
- `GET /api/projects/{projectId}/live2d/characters/{characterId}/versions?page=1&size=20`

选择一个可用 `versionId` 后查询组合素材：

`GET /api/projects/27/live2d/combo-motions/catalog?modelVersionId=419&page=1&size=50`

返回 `modelVersion`、`presets`、`motions` 和分页 `dialogues`。预设含参数绑定支持状态；保存模型中的动作 `sourceId` 为 `motion:{modelVersionId}:{模型包相对路径}`，`sourceVersionId` 固定为模型版本 ID。对白 `sourceId` 为 `dialogue:{dialogueId}`，`sourceVersionId` 为关联 `AUDIO_TRACK` asset ID。对白 `resourceUrl` 指向音轨，签名有效期为 600 秒；过期后重新获取 catalog 或组合详情。

```json
{
  "code":"OK","message":"Success","data":{
    "modelVersion":{"modelVersionId":"419","modelName":"许宁"},
    "presets":[{"presetId":"blink","supported":true,"parameterIds":["ParamEyeLOpen","ParamEyeROpen"],"missingParameterIds":[]}],
    "motions":[{"sourceId":"motion:419:rig/motions/idle.motion3.json","sourceVersionId":"419","group":"Idle","index":1,"durationMs":2800,"resourceUrl":"/api/projects/27/live2d/models/419/files/<ticket>/rig/motions/idle.motion3.json"}],
    "dialogues":{"items":[{"sourceId":"dialogue:731","sourceVersionId":"992","episodeId":"88","lineText":"你好。","sourceStartMs":6400,"durationMs":2100,"resourceUrl":"https://minio.example/video-audio/...","previewExpiresInSeconds":600}],"page":1,"size":50,"total":1}
  }
}
```

## 2. 编译预览

`POST /api/projects/27/live2d/combo-motions/compile` 同步返回编译结果。预览不要求先保存组合，可对草稿编译。预设片段使用 `trackType=MOTION`、`presetId`；已有模型动作也使用 `MOTION`，但 `presetId` 省略。台词使用 `trackType=DIALOGUE`。客户端不提交文件路径或临时 URL，Java 根据稳定素材 ID 解析并向 Python 提供受控共享文件引用。

```json
{
  "requestId":"d52ff31936ca4a9ebcdb921ef1963817",
  "modelVersionId":"419",
  "name":"入场动作",
  "durationMs":3200,
  "segments":[
    {"segmentId":"35c47f74998548c3a6fd7df4aa968fbc","trackType":"MOTION","presetId":"smile","sourceId":"preset:smile","sourceVersionId":"419","startTimeMs":0,"durationMs":1800},
    {"segmentId":"a649d7cf9c0a4824aec9ad5724f3fdac","trackType":"DIALOGUE","sourceId":"dialogue:731","sourceVersionId":"992","startTimeMs":900,"durationMs":1500}
  ]
}
```

Python 内部调用 `POST /internal/v1/live2d/combo-motions/compile`，必要时 Java 按同一 requestId 轮询 `GET /internal/v1/live2d/motion-tasks/{requestId}`。服务间请求同步等待编译完成；浏览器只看到 Java 返回的 HTTP 200。

```json
{
  "code":"OK","message":"Success","data":{
    "compilationId":"88ca116fc0a14a26af6506c96a2e50d0",
    "requestId":"d52ff31936ca4a9ebcdb921ef1963817",
    "contentHash":"4f3f...SHA-256...",
    "modelVersionId":"419","status":"READY","durationMs":3200,
    "issues":[],"missingCapabilities":[],
    "modelEntryUrl":"/api/projects/27/live2d/models/419/files/<ticket>/rig/model.model3.json",
    "playbackManifest":{
      "schemaVersion":2,"compileMode":"PARAMETER_CURVES","projectId":"27",
      "modelVersionId":"419","name":"入场动作","durationMs":3200,
      "segments":[
        {"segmentId":"35c47f74998548c3a6fd7df4aa968fbc","trackType":"MOTION","presetId":"smile","sourceId":"preset:smile","sourceVersionId":"419","startTimeMs":0,"durationMs":1800},
        {"segmentId":"a649d7cf9c0a4824aec9ad5724f3fdac","trackType":"DIALOGUE","sourceId":"dialogue:731","sourceVersionId":"992","startTimeMs":900,"durationMs":1500,"sourceStartMs":6400,"sourceDurationMs":2100,"resourceUrl":"https://minio.example/video-audio/...","previewExpiresInSeconds":600}
      ],
      "compiledMotion":{"formatVersion":1,"format":"cubism-motion3","durationMs":3200,"artifactSha256":"...","modelMocSha256":"...","resourceUrl":"https://minio.example/video-assets/...","previewExpiresInSeconds":600}
    }
  }
}
```

`READY` 可预览和保存。`CONFLICT` 返回 `issues` 与 `missingCapabilities`，不能保存；例如同一 Cubism 参数重叠控制返回 `PARAMETER_CONFLICT`，模型缺少动作绑定返回 `CAPABILITY_MISSING`。Java 返回的临时资源地址来自 MinIO 签名，过期后重新编译或重新查询保存详情。

播放时加载固定版本的 `modelEntryUrl`，再将 `compiledMotion.resourceUrl` 交给 Cubism 动作播放器。对话音频按 `startTimeMs` 调度，在源音轨 `sourceStartMs` 位置播放 `durationMs`。组合动作不合成新音频文件；语音音量曲线已编译进 Cubism `.motion3.json`。

## 3. 创建、更新与保存重试

保存 body 必须带编译时的完整字段和时间轴。Java 重新验证素材归属并计算内容哈希；草稿、名称、时长、素材 ID 或模型版本任一变化，旧 compilationId 即失效，需重新编译。保存时先将实际 `.motion3.json` 写入稳定对象键并验证可读取，再以事务发布历史记录。

创建：`POST /api/projects/27/live2d/combo-motions`，body 为编译请求并增加独立保存幂等键 `requestId` 和 `compilationId`：

```json
{
  "requestId":"c6f8980dd3b642dd9e6a5def79238352",
  "compilationId":"88ca116fc0a14a26af6506c96a2e50d0",
  "modelVersionId":"419","name":"入场动作","durationMs":3200,
  "segments":[
    {"segmentId":"35c47f74998548c3a6fd7df4aa968fbc","trackType":"MOTION","presetId":"smile","sourceId":"preset:smile","sourceVersionId":"419","startTimeMs":0,"durationMs":1800},
    {"segmentId":"a649d7cf9c0a4824aec9ad5724f3fdac","trackType":"DIALOGUE","sourceId":"dialogue:731","sourceVersionId":"992","startTimeMs":900,"durationMs":1500}
  ]
}
```

创建成功返回 HTTP 201 和组合详情，其中 `comboId` 是之后剧情节点可保存的稳定 ID。同 projectId/requestId 和相同内容重复提交返回同一个组合；相同 requestId 复用于不同内容返回 409 `LIVE2D_COMBO_CONFLICT`。

`POST` 和 `PUT` 成功响应都包含可编辑时间轴与可播放产物：

```json
{
  "code":"OK","message":"Success","data":{
    "comboId":"f56a8e26f3a34529be858cfac55be859",
    "projectId":"27","modelVersionId":"419","name":"入场动作","durationMs":3200,
    "status":"AVAILABLE","contentHash":"4f3f...SHA-256...",
    "savedAt":"2026-11-24T10:00:00Z","updatedAt":"2026-11-24T10:00:00Z",
    "timeline":{"projectId":"27","modelVersionId":"419","name":"入场动作","durationMs":3200,"segments":[{"segmentId":"35c47f74998548c3a6fd7df4aa968fbc","trackType":"MOTION","presetId":"smile","sourceId":"preset:smile","sourceVersionId":"419","startTimeMs":0,"durationMs":1800}]},
    "modelEntryUrl":"/api/projects/27/live2d/models/419/files/<ticket>/rig/model.model3.json",
    "playbackManifest":{"schemaVersion":2,"compileMode":"PARAMETER_CURVES","segments":[{"segmentId":"35c47f74998548c3a6fd7df4aa968fbc","trackType":"MOTION","sourceId":"preset:smile","sourceVersionId":"419","startTimeMs":0,"durationMs":1800}],"compiledMotion":{"formatVersion":1,"format":"cubism-motion3","durationMs":3200,"artifactSha256":"4f3f...","modelMocSha256":"c895...","resourceUrl":"https://minio.example/video-assets/...","previewExpiresInSeconds":600}}
  }
}
```

更新：`PUT /api/projects/{projectId}/live2d/combo-motions/{comboId}`，请求体同创建。组合 ID 不变，模型版本必须与创建时一致，已保存的可编辑时间轴和编译动作原子更新。

持久化失败返回 503 `LIVE2D_COMBO_SAVE_FAILED`；保存请求及 compilationId 保留，可调用 `POST /api/projects/{projectId}/live2d/combo-motions/save-requests/{requestId}/retry`。同一请求仍运行时返回 409 `LIVE2D_COMBO_SAVE_IN_PROGRESS`；服务中断导致 PROCESSING 超过 120 秒后，该重试接口可恢复。重复更新使用同一保存 requestId 幂等。

## 4. 列表、详情、编辑恢复和重播

- `GET /api/projects/{projectId}/live2d/combo-motions?page=1&size=20`：按更新时间倒序返回 `comboId`、固定模型版本、名称、总时长和状态。
- `GET /api/projects/{projectId}/live2d/combo-motions/{comboId}`：返回保存的 `timeline`、`contentHash`、固定模型入口和新签发的 `compiledMotion.resourceUrl`。

详情同时提供 `playbackManifest` 和 `timeline`。编辑器用 `timeline` 恢复轨道；播放时使用对应的历史模型版本和 `compiledMotion.resourceUrl`，对白继续读取每段的音轨 `resourceUrl`。关闭浏览器或重启 Java/Python 后，详情仍从数据库与 MinIO 恢复。地址过期时再次 GET 详情刷新。组合历史不跟随角色最新模型版本变化。

## 5. 错误码

| HTTP / code | 含义 |
|---|---|
| 400 `INVALID_REQUEST` / `LIVE2D_COMBO_INVALID` | ID、时间轴、时长或轨道结构不合法 |
| 404 `LIVE2D_COMBO_NOT_FOUND` | 项目、模型、动作、对白或音轨不存在或归属不匹配 |
| 409 `LIVE2D_COMBO_CONFLICT` | compilationId 过期、模型版本不匹配、内容哈希不一致或 requestId 复用冲突 |
| 409 `LIVE2D_COMBO_SAVE_IN_PROGRESS` | 保存仍在执行 |
| 503 `LIVE2D_COMBO_COMPILER_UNAVAILABLE` | Python 编译器/Core 不可用或超时 |
| 503 `LIVE2D_COMBO_SAVE_FAILED` | MinIO/数据库发布失败，可用原保存 requestId 重试 |
| 503 `VIDEO_STORAGE_UNAVAILABLE` | 历史所需模型、音轨或动作产物暂不可读 |
| 401 / 404 | 未登录 / 项目不属于当前用户（跨项目资源隐藏为 404） |

## 6. 迁移和运行账号

Flyway V13 创建编译快照、组合历史和保存幂等请求表。先由 Auth 执行迁移，再为 AI 运行账号授权：

```sql
GRANT SELECT, INSERT, UPDATE ON video_asset.live2d_combo_compilation TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT, UPDATE ON video_asset.live2d_combo_motion TO 'video_asset_ai'@'%';
GRANT SELECT, INSERT, UPDATE ON video_asset.live2d_combo_save_request TO 'video_asset_ai'@'%';
```

Java 与 Python 的 `LIVE2D_INPUT_ROOT`、`LIVE2D_OUTPUT_ROOT` 必须指向共享目录。编译请求只在该目录暂存所选模型清单、MOC、动作和音频副本；预览及已保存 `.motion3.json` 进入私有 MinIO `video-assets`。服务间 Python 路由不向浏览器开放。
