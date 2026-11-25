# Live2D 动作资产、版本和模型兼容性 API

基础路径为 `/api/projects/{projectId}/live2d`。业务请求使用 `Authorization: Bearer <accessToken>`；Java 校验当前用户、项目归属、动作、模型版本和片段来源。动作/模型/版本 ID 均以字符串传输，时间和关键帧位置使用整数毫秒。

## 目录和当前种子

仓库未找到共享的官方 20 组合定义。按现有 Python 实现，V14 以稳定 code 幂等初始化 `blink`、`smile`、`nod` 三个可编译基础动作和 `speech` 语音交接口型定义。Python 另外探测手臂/手型能力，但当前没有对应可编译预设。没有共享组合名称、轨道和关键帧定义时，不伪造 20 条官方数据；本 API 支持项目创建并发布 COMBO/CUSTOM 动作版本。

| 方法与路径 | 用途 |
|---|---|
| `GET /motions?type=&category=&keyword=&modelVersionId=&compatibilityStatus=&page=1&size=20` | 查询官方和项目动作。类型：`BASIC/COMBO/CUSTOM/DYNAMIC`；兼容状态：`COMPATIBLE/PARTIAL/INCOMPATIBLE` |
| `GET /motions/{motionId}` | 动作资产及当前发布版、草稿摘要 |
| `POST /motions` | 创建项目 BASIC/CUSTOM/COMBO 草稿 |
| `PUT /motions/{motionId}/draft` | 保存草稿并校验预期 revision |
| `POST /motions/{motionId}/duplicate` | 将官方或已发布版本另存为项目 CUSTOM 草稿 |
| `POST /motions/{motionId}/publish` | 发布不可变新版本 |
| `GET /motions/{motionId}/versions?page=1&size=20` | 查询已发布历史版本 |
| `GET /motion-versions/{versionId}` | 查询指定版本定义与固定片段引用 |
| `GET /models/{modelVersionId}/capabilities?refresh=false` | 查看该 MOC3 版本的参数/能力映射；无快照时调用 Python 分析 |
| `PUT /models/{modelVersionId}/capabilities/{capabilityId}` | 更新候选映射或确认映射 |
| `GET /motion-compatibility?modelVersionId={id}&motionVersionId={id}` | 计算固定动作版本对指定模型版本的兼容性 |

普通列表支持类型、分类和名称/code 关键词；增加 `modelVersionId` 后每项还返回 `compatibilityStatus`。按状态筛选时必须提供 modelVersionId。分页 `size` 上限 100。

## 动作定义与版本引用

草稿定义包括语义轨道、关键帧和可选版本化片段。关键帧 `value` 使用 0–1 归一化幅度，轨道方向为 `INCREASING/DECREASING/BOTH`，各轨道时间必须严格递增。COMBO 的 clip 可以引用已发布基础动作版本，或项目内已完成音轨素材。

```json
{
  "schemaVersion":1,
  "durationMs":1600,
  "handoffBehavior":null,
  "requiredCapabilities":["smile","nod"],
  "tracks":[],
  "clips":[
    {"clipId":"1d623a731227497291065534a28d3907","referenceType":"MOTION_VERSION","trackCode":"face","sourceMotionVersionId":"10000000000000000000000000000002","sourceAssetId":null,"replacementVersionId":null,"startMs":0,"durationMs":1600,"sourceStartMs":null},
    {"clipId":"39c4efb61f054cbaaaa29499f14c53da","referenceType":"AUDIO_ASSET","trackCode":"speech","sourceMotionVersionId":null,"sourceAssetId":992,"replacementVersionId":null,"startMs":200,"durationMs":1400,"sourceStartMs":6400}
  ]
}
```

创建草稿：`POST /api/projects/27/live2d/motions`

```json
{"actionType":"COMBO","category":"reaction","name":"微笑点头说话","description":"基础反应组合","definition":{"schemaVersion":1,"durationMs":1600,"handoffBehavior":"AUDIO_RMS_TO_MOUTH","requiredCapabilities":["smile","nod","speech"],"tracks":[],"clips":[{"clipId":"1d623a731227497291065534a28d3907","referenceType":"MOTION_VERSION","trackCode":"face","sourceMotionVersionId":"10000000000000000000000000000002","sourceAssetId":null,"replacementVersionId":null,"startMs":0,"durationMs":1600,"sourceStartMs":null}]}}
```

成功返回 HTTP 201，包含 `action` 和 `draftVersion`，其中 `revision` 用于后续并发校验。更新草稿 `PUT /motions/{motionId}/draft`：

```json
{"expectedRevision":1,"definition":{"schemaVersion":1,"durationMs":1600,"handoffBehavior":null,"requiredCapabilities":["smile"],"tracks":[{"trackId":"face","semanticTrack":"face","capabilityId":"smile","direction":"INCREASING","behavior":null,"keyframes":[{"timeMs":0,"value":0},{"timeMs":500,"value":0.8},{"timeMs":1600,"value":0}]}],"clips":[]}}
```

旧 revision 返回 409 `LIVE2D_MOTION_VERSION_CONFLICT`。官方动作不可直接编辑；另存接口复制指定 `sourceVersionId`，产生项目 CUSTOM 草稿。发布 `POST /motions/{motionId}/publish`，body `{"expectedRevision":2}`。发布在单个事务中分配递增 versionNumber、固定动作定义和引用并更新 currentVersion。已发布版本不覆盖、不物理删除，旧剧情可继续引用旧 versionId。

## 模型能力与兼容状态

能力分析通过 Java 现有 Python 调用，输入固定 `modelVersionId` 对应的 model3/MOC3。Python 返回 MOC3 参数、范围、默认值、可见驱动方向及能力；Java 将结果按模型版本保存。新 modelVersionId 独立分析，不继承别的模型确认状态。`refresh=true` 重试 Python 分析；失败会返回稳定错误码，并保留原有已确认映射。

响应同时返回全部 MOC 参数（范围、默认值、是否有可见驱动）和每项 capability 的映射快照，前端可用它选择参数后进行确认：

```json
{"code":"OK","message":"Success","data":{"modelVersionId":"421","analysisStatus":"READY","errorCode":null,"errorMessage":null,"parameters":[{"id":"ParamMouthForm","min":-1,"max":1,"defaultValue":0,"active":true,"activeDecreasing":true,"activeIncreasing":true}],"capabilities":[{"modelVersionId":"421","capabilityId":"mouth-form","mappingStatus":"CANDIDATE","parameterIds":["ParamMouthForm"],"missingParameterIds":[],"evidence":["RUNTIME_PROBE"],"assets":[],"partIds":[],"reason":"候选模型绑定","assetFingerprint":"...","source":"PYTHON","updatedAt":"2026-11-25T02:00:00Z"}]}}
```

能力映射状态：

- `MISSING`：模型缺少所需参数。
- `PRESENT`：参数存在但 Python 没检测到可见驱动绑定。
- `CANDIDATE`：检测到绑定，尚未由用户确认。
- `UNKNOWN`：参数清单不完整，或当前证据不足以确认语义。
- `CONFIRMED`：用户按当前模型版本确认，可用于判定可播。

手动确认：`PUT /api/projects/27/live2d/models/421/capabilities/smile`：

```json
{"status":"CONFIRMED","parameterIds":["ParamMouthForm"]}
```

Java 校验参数属于该模型版本；CONFIRMED 还要求参数具备实际可见驱动。Python 分析失败时，此接口保留上次快照，并返回 `analysisStatus=FAILED`、稳定 `errorCode` 和错误摘要；再次 GET 或传 `refresh=true` 可重试，不需要删除历史确认映射。

兼容查询展开组合引用，将语义轨道映射到该模型的真实参数。多个轨道映射同一参数时，按半开区间检查重叠并返回具体参数、片段和区间。speech handoff 返回 `runtimeBehaviors`，例如 `AUDIO_RMS_TO_MOUTH`，由前端播放器对齐音频和嘴部控制。

```json
{"code":"OK","message":"Success","data":{"motionVersionId":"50000000000000000000000000000001","modelVersionId":"421","status":"PARTIAL","presentCapabilities":[],"candidateCapabilities":["smile"],"confirmedCapabilities":["nod"],"missingCapabilities":[],"conflicts":[{"parameterId":"ParamAngleY","clipIds":["1d623a731227497291065534a28d3907","39c4efb61f054cbaaaa29499f14c53da"],"startMs":300,"endMs":700}],"missingSegments":[],"runtimeBehaviors":[],"alternatives":[]}}
```

状态为 `COMPATIBLE`、`PARTIAL` 或 `INCOMPATIBLE`。缺少 capability 时 `missingSegments` 返回片段、能力和可选 replacementVersionId。

## 剧情引用

当前仓库没有剧情节点持久化模块，因此本轮只提供固定版本引用格式供播放器和后续剧情模块联调，不新增虚构的剧情 API：

```json
{"type":"LIVE2D_MOTION","motionId":"<asset-id>","motionVersionId":"<published-version-id>"}
```

剧情必须保存 `motionVersionId`，不能仅存 motionId 或 latest。台词偏移保存在 `sourceStartMs`，运行时播放轨道响应中的 `AUDIO_RMS_TO_MOUTH` handoff。

## 错误码和数据库

| HTTP / code | 含义 |
|---|---|
| 400 `LIVE2D_MOTION_INVALID` | 定义、时间、幅度或方向无效 |
| 403 `FORBIDDEN` | 官方资产只读，先另存自定义动作 |
| 404 `LIVE2D_MOTION_NOT_FOUND` | 动作或版本不存在/跨项目访问 |
| 409 `LIVE2D_MOTION_VERSION_CONFLICT` | revision 已变化 |
| 422 `LIVE2D_MOTION_REFERENCE_INVALID` | 引用未发布、越项目或素材类型错误 |
| 503 `LIVE2D_MOTION_COMPATIBILITY_UNAVAILABLE` | Python Core 能力分析不可用，可 refresh 重试 |

Flyway V14 创建资产、版本、固定片段引用、模型参数快照和能力映射表；幂等初始化当前 Python 支持的三个基础预设及 speech handoff。Auth 自动运行迁移后，AI 运行账号授权 SQL 见 `docs/database.md` 的 V14 段落。
