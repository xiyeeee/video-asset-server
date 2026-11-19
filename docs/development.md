# 本地开发与验收

需要 JDK 21、Docker Compose v2 或兼容版本。仓库已提供 Maven 3.9 Wrapper。Windows 可使用 `mvnw.cmd`，下方命令使用 Bash 或 Zsh。

## 首次启动

```bash
cp .env.example .env
docker compose up -d
docker compose ps -a
```

在 Nacos 3 控制台 `http://127.0.0.1:18848` 首次初始化 `nacos` 管理员密码，使用与 `.env` 中 `NACOS_PASSWORD` 一致的值。五个常驻容器应为 healthy，`minio-init` 完成后应退出且退出码为 0。端口冲突时编辑 `.env`，例如将 `NACOS_PORT` 和 `NACOS_GRPC_PORT` 配成 18849 和 19849。后者始终等于前者加 1000。

生成仅用于开发验收的 RSA 密钥对，并构建工程。

```bash
mkdir -p .local
(umask 077; openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .local/jwt-private.pem)
openssl pkey -in .local/jwt-private.pem -pubout -out .local/jwt-public.pem
./mvnw clean verify
```

在仓库根目录打开四个终端，或使用 IDEA 分别运行四个启动类。每个终端先导出开发配置，再依次启动 Gateway、Auth、Media、AI。自定义 `.env` 若由 Shell 读取，需采用 Shell 兼容的赋值和引号。IDEA 可直接把对应变量添加到 Run Configuration。

```bash
set -a
. ./.env
set +a

java -jar video-asset-gateway/target/video-asset-gateway-0.0.1-SNAPSHOT.jar
# 其余命令各在已导出相同环境变量的终端执行
java -jar video-asset-auth/target/video-asset-auth-0.0.1-SNAPSHOT.jar
java -jar video-asset-media/target/video-asset-media-0.0.1-SNAPSHOT.jar
java -jar video-asset-ai/target/video-asset-ai-0.0.1-SNAPSHOT.jar
```

## Navicat 连接本机 MySQL

在 Navicat 新建 MySQL 连接。本机联调用的 Compose 项目为 `video-asset-verify`。

| 字段 | 本机联调配置 |
| --- | --- |
| 连接名 | 视频转素材（本地） |
| 主机 | `127.0.0.1` |
| 端口 | `13306` |
| 用户名 | `video_asset_auth` |
| 密码 | `.local/idea-dev.properties` 中的 `MYSQL_AUTH_PASSWORD` |
| 数据库 | `video_asset` |

测试连接成功后保存。常用表为 `project`（项目）、`video_episode`（集数）和 `asset`（素材）。
`video_episode.source_asset_id` 对应 `asset.id`，可查找每集的原视频记录。

在其他机器上，端口取实际 Compose 环境文件中的 `MYSQL_PORT`，项目默认值为 `3306`。
本机可用下方命令核对映射端口。

```bash
docker compose --env-file .local/verify.env -p video-asset-verify port mysql 3306
```

## 验证

在导出同一份开发环境变量的终端执行。脚本使用 Python 3 标准库，可选用 OpenSSL 和本地私钥生成测试 Token。脚本验证 Nacos 健康实例、四个 Actuator、三个路由、直连及网关侧的 401、Gateway CORS。启用 JWT 测试后，合法 Token 会通过鉴权并在不存在的验证路径得到 404，错误 Token 应得到 401。

```bash
python3 scripts/smoke.py --jwt-private-key .local/jwt-private.pem
```

验证 Nacos 配置导入时，在控制台的 `VIDEO_ASSET` 组为 `video-asset-auth.yaml`、`video-asset-media.yaml` 和 `video-asset-ai.yaml` 添加以下 YAML，然后重启对应服务，再给脚本传入 `--marker nacos`。Gateway 也会在启动时读取自己对应的 `video-asset-gateway.yaml`。

```yaml
app:
  dev:
    probe-marker: nacos
```

Auth 的健康汇总检查 MySQL 和 Redis，AI 汇总检查 RabbitMQ 和 Redis，Media 汇总通过 SDK 验证五个 Bucket。Flyway 启动日志应出现 V1 成功记录，后续重启应显示库已是最新版本。需要单独检查时，参照 [基础设施](infrastructure.md) 和 [数据库](database.md) 中的命令。

## 日志与任务排查

服务默认将日志输出到启动终端。可在启动 Gateway 时追加 `> .local/gateway.log 2>&1` 保存日志。从请求摘要取出 `traceId`，用下方命令按字段精确查找。当前 `traceId` 来自 Gateway 的 WebFlux 请求 ID，用于 Gateway 进程内的请求定位。

```bash
rg 'traceId=替换为实际值( |$)' .local/gateway.log
```

当前已有 `ai_task` 表。可在开发环境用任务主键检查任务状态和尝试次数，把示例值 `123` 替换为实际数字 ID。

```bash
docker compose exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot video_asset -e "SELECT id, episode_id, status, attempt_count FROM ai_task WHERE id = 123;"'
```
