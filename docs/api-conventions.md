# API 约定

## 路径和版本

网关路径为 `/api/{auth|media|ai}/v1/{resources}`，下游 Controller 使用 `/v1/{resources}`。资源路径使用小写、复数和连字符，例如 `GET /api/media/v1/projects/{projectId}/episodes`。GET 查询，POST 创建，PATCH 部分更新，PUT 完整替换，DELETE 删除。破坏性更改用新主版本，并在迁移期并行提供旧版本。

## 响应与错误

业务 JSON 采用 `Result<T>`，成功示例为 `{"code":"OK","message":"Success","data":{"id":"123"}}`。错误响应 `data` 为空，HTTP 状态仍表达结果。当前错误码为 `INVALID_REQUEST`（400）、`UNAUTHENTICATED`（401）、`FORBIDDEN`（403）、`NOT_FOUND`（404）、`INTERNAL_ERROR`（500）。框架异常保持原状态，可使用 `HTTP_<status>`。后续领域错误码使用大写下划线格式，如 `MEDIA_PROJECT_NOT_FOUND`。前端以 `code` 判定逻辑，`message` 用于展示或诊断。Actuator、二进制下载、网关生成的协议错误使用各自原生格式。

## 分页、时间和命名

分页用 `page`（从 1 开始，默认 1）、`size`（默认 20，上限 100）。响应 `data` 包含 `items`、`page`、`size`、`total`。分页排序应加入唯一字段作为最后排序项以保证稳定。审计时间使用 ISO 8601 UTC，例如 `2026-11-19T02:00:00Z`。集内偏移字段以 `Ms` 结尾，例如 `startMs`、`endMs`。64 位业务 ID 作为十进制字符串输出，避免 JS 精度截断。

JSON 字段使用 camelCase。请求 DTO 采用 `CreateProjectRequest`、`ProjectQuery`，响应 VO 使用 `ProjectVO`。跨服务共享 DTO 仅定义契约字段，例如 `AiTaskDTO`，不暴露持久化实体。

## 服务间调用

面向用户的请求透传 `Authorization: Bearer …`。接收端验证签名、签发者、受众、期限和资源权限。后续 Feign 客户端使用契约模块中的服务名，通过发现、负载均衡和明确的超时访问目标服务。机器调用使用独立服务凭证，有副作用的请求依据幂等键约定重试。此阶段的 `/internal/dev/ping` 只服务于开发连通性验证。
