# Live2D 图片转模型前端联调

所有路径都请求 Java 网关，本地默认端口为 `18080`。图片上传、任务提交和任务查询均需 `Authorization: Bearer <accessToken>`。项目所有者才能操作，其他用户访问项目、任务或源素材返回 `404 NOT_FOUND`。项目、素材及模型的数字 ID 建议用字符串保存。

## 1. 选择或上传图片

已有角色素材使用人物提取接口返回的素材 `id`，提交时放入 `sourceAssetId`。素材必须是当前项目的 `CHARACTER_IMAGE`。

本地图片调用 `POST /api/projects/{projectId}/files`，以 `multipart/form-data` 的 `file` 字段上传。支持 PNG、JPEG 和 WebP，单张最大 10 MiB。用 `FormData` 时让浏览器自动设置带 boundary 的 Content-Type。接口成功时返回 HTTP 201。

```json
{
  "code": "OK",
  "message": "Success",
  "data": { "uploadFileId": "418", "contentType": "image/png", "sizeBytes": 182430 }
}
```

## 2. 提交任务

`POST /api/projects/{projectId}/live2d/tasks`，请求体为 JSON。填写 `modelName`（去除首尾空白后 1 至 255 字符），并在 `sourceAssetId` 和 `uploadFileId` 中选择一个。示例使用已有角色素材。

```json
{ "modelName": "末班车角色", "sourceAssetId": "312" }
```

使用本地上传时，请求体为 `{"modelName":"末班车角色","uploadFileId":"418"}`。HTTP 202 表示任务已入队。提交按钮应在请求期间禁用，每次有效提交都会生成一个任务。

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "taskId": "5ccbb13c4521481ab0756b37fc309238",
    "status": "QUEUED",
    "stage": null,
    "result": null,
    "error": null
  }
}
```

## 3. 接收任务状态

提交成功后连接 `WebSocket /api/projects/{projectId}/live2d/tasks/{taskId}/events`。浏览器不能给 WebSocket 设置 `Authorization`，因此握手时同时提供两个子协议：`video-asset.bearer` 和访问令牌。服务端只回选 `video-asset.bearer`，令牌不会出现在地址里。

连接建立后服务端立即发送当前任务快照，之后每次状态变化再发一条。消息体就是查询接口 `data` 中的任务对象，没有外层 `{code,message,data}`。收到 `SUCCEEDED` 或 `FAILED` 后关闭连接。离开页面或切换项目时也要关闭，避免旧任务覆盖当前项目。连接中途断开可以重新握手，新连接会再次收到当前快照，不需要按固定间隔查询。

`GET /api/projects/{projectId}/live2d/tasks/{taskId}` 仍保留。模型入口票据过期时用它取新的 `modelEntryUrl`。401 时走项目已有的登录失效流程。

| status | 含义 |
| --- | --- |
| `QUEUED` | 任务已保存，等待处理 |
| `RUNNING` | Python 正在处理，或 Java 正在校验并发布模型资源 |
| `SUCCEEDED` | 模型入口和依赖均已发布，`result` 可用 |
| `FAILED` | 终态，`error.code` 和 `error.message` 可用。需要重试时重新提交任务 |

`stage` 可为空。已知值包括准备图片 `PREPARING`、生成模型 `GENERATING`、验证模型 `VALIDATING`、导出 `EXPORTING`。Java 发布期间可保留最后一个 Python 阶段。

成功时返回 HTTP 200，示例票据用 `<ticket>` 表示。

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "taskId": "5ccbb13c4521481ab0756b37fc309238",
    "status": "SUCCEEDED",
    "stage": "EXPORTING",
    "result": {
      "modelId": "419",
      "modelEntryUrl": "/api/projects/27/live2d/models/419/files/<ticket>/runtime/model.model3.json"
    },
    "error": null
  }
}
```

失败也返回 HTTP 200。任务结果示例

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "taskId": "5ccbb13c4521481ab0756b37fc309238",
    "status": "FAILED",
    "stage": "EXPORTING",
    "result": null,
    "error": {
      "code": "ARTIFACT_MISSING",
      "message": "生成服务未发布完整的模型包",
      "stage": "EXPORTING"
    }
  }
}
```

`ARTIFACT_INVALID` 表示模型包或依赖不完整，`LIVE2D_TASK_TIMEOUT` 表示任务到达处理期限，`CONVERSION_FAILED` 表示 Python 转换失败。其他生成错误也使用同一结构。HTTP 400 表示请求字段或图片类型有误，HTTP 413 表示上传超限。

## 4. 浏览器加载模型

将 `modelEntryUrl` 原样交给支持 Cubism 3+ 的加载器。同源或使用开发代理时可直接使用该路径。网关独立部署时用 `new URL(modelEntryUrl, gatewayOrigin).href`，网关需允许前端来源。加载器按入口 URL 解析清单中的相对路径，自动读取同一地址目录下的 `.moc3`、贴图和其他依赖。

该路径已携带仅对该项目和模型生效的短期访问票据，资源 GET 不需追加 Bearer。票据有效期 10 分钟，过期的资源返回 404。发生过期时用 Bearer 重新查询原任务，拿新入口 URL 重新加载。素材列表或本地持久化应保存 `modelId` 和 `taskId`，入口 URL 只在本次加载使用。

## 联调环境

Auth 先应用数据库 V11，Java AI 与网关使用新版。Java 配置 `LIVE2D_PYTHON_URL`，并与 Python 共享 `LIVE2D_INPUT_ROOT` 和 `LIVE2D_OUTPUT_ROOT` 对应的物理目录。Java 写输入、读输出，Python 读输入、写输出。分进程部署时应配置共享用户组或容器挂载权限。Python 需启用实际转换器、Cubism Core 校验和可用的图层分解服务，才能转换新图片。
