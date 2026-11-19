# 视频转素材平台后端

基于 Java 21、Spring Boot 3.5 和 Spring Cloud 2025.0 的 Maven 多模块项目。当前提供服务发现、路由、独立 JWT 校验、数据库迁移和中间件连接。

```bash
cp .env.example .env
docker compose up -d
docker compose ps -a
./mvnw clean verify
```

首次启动需要初始化 Nacos 管理员并生成开发 JWT 公钥。服务启动命令、端口冲突处理和测试步骤见 [开发指南](docs/development.md)。

| 模块 | 职责 | 本地端口 |
| --- | --- | --- |
| `video-asset-common` | 响应、错误、Servlet 鉴权、开发探针、Redis 配置 | — |
| `video-asset-api` | 服务名称和后续 DTO、Feign 契约位置 | — |
| `video-asset-gateway` | WebFlux、发现路由、负载均衡、CORS、请求日志 | 8080 |
| `video-asset-auth` | BCrypt、JWT 资源服务、MyBatis-Plus、Flyway、Redis | 8081 |
| `video-asset-media` | MinIO 客户端及私有 Bucket 健康检查 | 8082 |
| `video-asset-ai` | RabbitMQ 拓扑和 Redis | 8083 |

其他文档见 [架构](docs/architecture.md)、[基础设施](docs/infrastructure.md)、[数据库](docs/database.md)、[API 约定](docs/api-conventions.md)。初始化要求归档在 [原始提示词 v1.0.1](docs/prompts/v1.0.1.md)。
