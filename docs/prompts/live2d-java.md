你负责「视频转素材」项目的 Spring Boot 后端 video-asset-server。本轮把真实的图片转 Live2D 任务接入现有项目、人物素材和文件管理流程。先阅读仓库的 AGENTS.md、开发规范和现有代码，确认鉴权、任务、文件存储和统一响应格式后再改动。\
\
一、提供两类业务接口\
\
1\. 提交图片转模型任务。路径遵循项目现有规范，建议 POST /api/projects/{projectId}/live2d/tasks。\
&#x20;  \- 输入 modelName，以及 sourceAssetId 或 uploadFileId，二者必填其一。前端本地上传复用现有文件上传接口。\
&#x20;  \- 校验项目访问权限、文件归属、图片类型和文件限制。使用服务端生成的文件引用交给 Python。\
&#x20;  \- 返回 taskId、status，让前端异步查询。\
2\. 查询任务。建议 GET /api/projects/{projectId}/live2d/tasks/{taskId}。\
&#x20;  \- 返回 taskId、status 和可选 stage。\
&#x20;  \- 成功时返回 result.modelId、result.modelEntryUrl。\
&#x20;  \- 失败时返回 error.code、error.message，可选 error.stage。\
&#x20;  \- 所有接口遵循项目已有的响应包装。\
\
二、和 Python 对接并保存产物\
\
1\. Java 向 Python 提交 taskId、projectId、modelName、sourceImageRef。状态统一为 QUEUED、RUNNING、SUCCEEDED、FAILED。stage 字段可选。\
2\. Python 成功时返回 result.manifestPath 和 result.artifactRef。Java 负责取回模型包，沿用现有存储方案保存并登记模型资产。任务查询重复调用时，同一个 taskId 只登记一个模型资产。\
3\. modelEntryUrl 必须能让浏览器加载 .model3.json，并通过其相对路径加载 .moc3、贴图和其他依赖。保持目录结构，资源访问遵循项目现有鉴权方式。归档解包时校验目标路径和解包大小。模型资产可访问后再向前端返回 SUCCEEDED。\
4\. Python 失败、超时、产物缺失时，按现有任务机制处理，提供明确的错误码和用户可理解的错误信息。保留任务 ID、调用耗时和状态变化日志，日志中避免输出密钥和带凭证的 URL。\
\
三、开发与验收\
\
\- 使用显式 Java 类型，遵循 IDEA 格式。关键流程写简洁中文注释。DTO、VO 和持久化对象沿用仓库现有约定。\
\- 复用现有中间件、任务和文件存储方案。\
\- 验证本地上传与已有角色素材都能发起任务，项目权限生效，生成成功后前端可读取入口与贴图，失败时有清晰状态。\
\- 将本轮开发提示词原样保留到当前仓库 docs/prompts/live2d-java.md。\
\- 输出前端联调文档，列出真实接口路径、请求与响应样例、状态含义，以及模型资源的鉴权和加载方式。
