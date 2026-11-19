# 基础设施

Compose 仅用于本地开发。常驻服务使用独立持久化卷与项目 bridge 网络，宿主端口仅绑定到 127.0.0.1。样例值是开发占位值，真实配置由环境变量或部署平台注入。

| 服务 | 固定版本 | 宿主端口 |
| --- | --- | --- |
| MySQL | `mysql:8.4.10` | 3306 |
| Redis | `redis:7.4.9-alpine` | 6379 |
| RabbitMQ | `rabbitmq:4.2.8-management` | 5672，管理 15672 |
| Nacos | `nacos/nacos-server:v3.0.3` | HTTP 8848，客户端 gRPC 9848，控制台 18848 |
| MinIO | 本地镜像 `video-asset-minio:RELEASE.2025-09-07T16-13-09Z` | API 9000，控制台 9001 |

```bash
docker compose up -d
docker compose ps -a
docker compose logs -f nacos mysql redis rabbitmq minio
docker compose stop
docker compose down
# 明确需要清空本项目的全部开发数据时执行
docker compose down -v
```

## Nacos

Nacos 在 MySQL 通过健康探针后启动，使用独立的 `nacos_config` schema 和 DML 账号。官方表脚本在 MySQL 空卷初始化时执行，管理员密码通过启动后的控制台建立。默认注册组和配置组为 `VIDEO_ASSET`，分别可通过 `NACOS_DISCOVERY_GROUP` 和 `NACOS_CONFIG_GROUP` 覆盖。默认 namespace 是 `public`。

各服务使用 Spring Boot Config Data `spring.config.import` 读取相同应用名的 YAML Data ID。开发使用 `optional:nacos:`，生产使用 `nacos:`。当前 Alibaba 客户端收到空配置或缺少 Data ID 时会记录警告并继续启动。依赖远程配置的生产环境，应在部署前确认四份 Data ID 已发布。配置在启动时读取，更新后重启服务。生产用 `NACOS_SERVER_ADDR`、`NACOS_NAMESPACE`、`NACOS_USERNAME`、`NACOS_PASSWORD` 指定目标环境和有权限的服务账号。若自动识别的地址不符合网络拓扑，设置 `SERVICE_ADVERTISE_IP` 为 Gateway 能访问的地址。生产配置中心只存非敏感配置。[Nacos 配置导入](https://sca.aliyun.com/en/docs/2025.x/user-guide/nacos/quick-start/) · [Nacos 管理员初始化](https://nacos.io/en/docs/v3.2/manual/admin/auth/)

## Redis 和 RabbitMQ

Auth 和 AI 使用非响应式 Spring Data Redis、Lettuce、2 秒连接和命令超时。RedisTemplate 使用字符串 key 和 Jackson JSON 树 value。开发 Redis 使用密码及 AOF。生产使用 `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`，默认启用 TLS。

AI 由 Spring Boot 自动配置缓存型 RabbitMQ ConnectionFactory，开启 correlated confirm、publisher returns 和 mandatory。将来发布业务需要处理 confirm、nack 和退回事件，持久化发布结果。生产使用 `RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_USERNAME`、`RABBITMQ_PASSWORD`、`RABBITMQ_VHOST`，默认启用 TLS。

| 作用 | Exchange | Routing key | Queue |
| --- | --- | --- | --- |
| 任务 | `video.asset.ai` | `task.requested` | `video.asset.ai.task` |
| 死信 | `video.asset.ai.dlx` | `task.dead` | `video.asset.ai.task.dead` |

队列是持久化 quorum queue。任务队列的 broker delivery limit 为 3，监听端默认拒绝时不重新入队，应用端重试默认关闭。后续消费按任务 ID / 幂等键做重复投递校验，业务事务提交后 ACK。需要延迟重试时，可单独设计有次数上限的延迟拓扑。

```bash
docker compose exec -T redis sh -ec 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping'
docker compose exec -T rabbitmq rabbitmqctl -q list_queues -p video_asset name type arguments
docker compose exec -T rabbitmq rabbitmqctl -q list_bindings -p video_asset source_name destination_name routing_key
```

## MinIO

本地镜像从 Alpine 3.23.4 构建，下载固定 MinIO Server 和 `mc` RELEASE.2025-08-13T08-35-41Z 的官方发布二进制，并按 CPU 架构校验 SHA-256。支持 arm64 和 amd64。仓库内 Dockerfile 固定服务端和 CLI 版本，Java SDK 版本独立由 Maven 管理。替换生产存储时应使用受维护的部署和独立应用凭证。[MinIO Binary Release](https://github.com/minio/minio/releases/tag/RELEASE.2025-09-07T16-13-09Z) · [MinIO Client Release](https://github.com/minio/mc/releases/tag/RELEASE.2025-08-13T08-35-41Z)

`minio-init` 创建私有的 `video-original`、`video-frames`、`video-audio`、`video-assets`、`video-exports`，可用 `docker compose run --rm minio-init` 重新执行。生产预先创建同名私有 Bucket，以 `MINIO_ENDPOINT`、`MINIO_ACCESS_KEY` 和 `MINIO_SECRET_KEY` 配置 Media。原始视频保持私有，后续在资源授权后发放短期预签名 URL。开发检查匿名访问可对单个 Bucket 发起 `curl -i 'http://127.0.0.1:9000/video-original?location'`，预期 HTTP 403。

## 生产部署变量

通过部署平台注入 Nacos、数据库、Redis、RabbitMQ、MinIO 凭证。Auth、Media、AI 均需要 `JWT_ISSUER`、`JWT_AUDIENCE` 和 `JWT_PUBLIC_KEY_LOCATION`。公钥用 X.509 PEM，私钥仅由签发端保管。Gateway 的 `GATEWAY_ALLOWED_ORIGINS` 为逗号分隔的明确来源列表。环境差异使用 `dev` / `prod` Profile，生产显式设置 `SPRING_PROFILES_ACTIVE=prod`。
