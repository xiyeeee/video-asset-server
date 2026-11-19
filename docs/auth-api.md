# 注册、登录与邮箱验证

Gateway 路径使用 `/api/auth/v1`，Auth Controller 路径使用 `/v1`。响应沿用 `{code,message,data}`，用户 ID 为字符串。

| 方法 | 路径 | 请求与结果 |
| --- | --- | --- |
| POST | `/api/auth/v1/email-codes` | `{"email":"user@example.test","purpose":"REGISTER"}`，返回 `200`，`data.retryAfterSeconds` 为秒 |
| POST | `/api/auth/v1/register` | `email`、`username`、`verificationCode`、`password`、`confirmPassword`，成功 `201` |
| POST | `/api/auth/v1/login` | `{"account":"user_1","password":"test-password"}`，成功 `200` |
| GET | `/api/auth/v1/me` | `Authorization: Bearer <token>`，成功 `200` |

注册和 `/me` 的 `data` 为 `{"id":"123","username":"user_1","email":"user@example.test","displayName":"user_1"}`。登录的 `data` 为 `{"accessToken":"<JWT>","tokenType":"Bearer","expiresIn":7200,"user":{…}}`。已有账号的邮箱可以为空。用户名 3–32 位字母、数字或下划线，邮箱和用户名规范化为小写，邮箱保留点和加号部分。注册密码至少 8 字符且最多 72 个 UTF-8 字节，保留用户输入原值。验证码必须用字符串传递。

| HTTP | 错误码 | 处理 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 检查输入 |
| 400 | `VERIFICATION_INVALID` | 验证码无效、过期、已使用或错误次数耗尽 |
| 401 | `INVALID_CREDENTIALS` / `UNAUTHENTICATED` | 登录失败或当前凭证无效 |
| 409 | `ACCOUNT_CONFLICT` | 邮箱或用户名唯一约束冲突 |
| 429 | `RATE_LIMITED` | 读取 `Retry-After`，单位秒 |
| 503 | `MAIL_UNAVAILABLE` / `AUTH_UNAVAILABLE` | 邮件或认证依赖暂不可用 |

验证码有效期 5 分钟，发送冷却 60 秒，单个挑战允许 5 次错误尝试。已注册邮箱请求验证码返回通用受理提示。Redis 限流覆盖发送、注册及所有登录尝试。请求来源默认使用 Auth 看到的连接地址，经 Gateway 时可能共用来源配额。生产若需要按公网客户端地址细分，需在受信代理层配置可信转发来源和验证直连隔离。

登录签发 RS256 JWT。退出由浏览器删除本地会话，既有 JWT 按有效期失效。`/me` 检查用户当前状态。业务接口的资源授权随业务模块实现。

## 数据库权限

Auth 的 Flyway 从 V2 为现有用户表增加可空邮箱与验证时间，并创建挑战表。现有开发运行账号默认只有读取权限。执行迁移后，由数据库管理员给 Auth 运行账号增补表级写入权限，保留与 Flyway 迁移账号分离。下列命令用于本地默认 Compose 环境。

```bash
docker compose exec -T mysql sh -ec 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot' <<'SQL'
GRANT INSERT ON video_asset.sys_user TO 'video_asset_auth'@'%';
GRANT INSERT, UPDATE ON video_asset.auth_email_challenge TO 'video_asset_auth'@'%';
SQL
```

## SMTP 与签名配置

配置同一对 RSA 的公钥和 Auth 私钥。`JWT_PRIVATE_KEY_LOCATION` 指向 PKCS#8 PEM，例如 `file:/secure/path/jwt-private.pem`。开发可用现有本地密钥对。生成独立 HMAC 密钥时使用 `openssl rand -base64 32`，结果通过受保护的环境配置注入。

| 变量 | 用途 |
| --- | --- |
| `JWT_PRIVATE_KEY_LOCATION` | 只给 Auth 注入的签发私钥路径 |
| `AUTH_CODE_HMAC_KEY` | 至少 32 字节的 Base64 编码密钥 |
| `EMAIL_CODE_ENABLED` | 启用实际邮件发送 |
| `SMTP_HOST`、`SMTP_PORT` | SMTP 服务器 |
| `SMTP_USERNAME`、`SMTP_PASSWORD`、`SMTP_FROM` | SMTP 账号、授权码、发件地址 |
| `SMTP_AUTH`、`SMTP_SSL`、`SMTP_STARTTLS` | 安全及认证选项，按服务商配置 |

QQ 等使用 465 隐式 TLS 的环境令 `SMTP_SSL=true`、`SMTP_STARTTLS=false`。使用 587 的 STARTTLS 环境令 `SMTP_SSL=false`、`SMTP_STARTTLS=true`。本地 Mailpit 关闭认证与 TLS，发送到测试域名并从 Mailpit 收件页取码。真实 SMTP 凭证应从仓库外注入，不写入 Nacos 或提交到 Git。

前端开发服务器将 `/api` 代理到 Gateway，默认 `127.0.0.1:8080`，可用 `API_PROXY_TARGET` 覆盖。如果 Vite 改用其他端口，将浏览器访问的 Origin 加入 Gateway 的 `GATEWAY_ALLOWED_ORIGINS`。生产反向代理需保留该前缀。
