# 后端架构

浏览器通过 Gateway 访问业务服务。Gateway 使用 WebFlux，通过 Nacos 发现实例并由 Spring Cloud LoadBalancer 解析 `lb://` URI。仅配置下表的三个业务前缀。每条路由执行 `StripPrefix=2`，服务扩缩容由注册表反映到路由。

| 入口 | 服务 | 下游路径示例 |
| --- | --- | --- |
| `/api/auth/**` | `video-asset-auth` | `/api/auth/internal/dev/ping` → `/internal/dev/ping` |
| `/api/media/**` | `video-asset-media` | `/api/media/internal/dev/ping` → `/internal/dev/ping` |
| `/api/ai/**` | `video-asset-ai` | `/api/ai/internal/dev/ping` → `/internal/dev/ping` |

三个下游服务都使用 Spring Security 6，按无状态 OAuth2 Resource Server 处理 Bearer JWT，独立验证 RS256 签名、签发者、受众和过期时间。每个服务自行负责资源级授权。Gateway 透传 Bearer 请求，日志记录请求方法、路由 ID、状态码和耗时。下游服务端口在生产环境部署于私网，且仅由受信调用方访问。未来签发端保管私钥，验证端只配置公钥。跨服务身份只取自已验证的 Token。

当前匿名白名单允许 GET `/actuator/health`。仅当启用 `dev` 且未启用 `prod` 时，开放 GET `/internal/dev/ping`。Actuator 只暴露健康状态。浏览器跨域来源由 Gateway 显式配置。认证基础使用 Bearer Header 和无状态会话配置。

公共模块的 Spring Web、Security 和 Redis 依赖为可选依赖。业务模块显式选择所需 Starter。契约模块当前只有服务名常量，等实际跨服务接口出现后再加入 DTO 和 Feign 契约。Auth 是唯一迁移模块。Media、AI 分别负责对象存储和消息基础设施，未来各自申请表级运行账号。

## 版本

| 项目 | 版本 |
| --- | --- |
| Java / Maven Wrapper | 21 / 3.9.16（Wrapper 3.3.4） |
| Spring Boot / Spring Cloud | 3.5.16 / 2025.0.3 |
| Spring Cloud Alibaba / Nacos Client | 2025.0.0.0 / 3.0.3 |
| Spring Cloud Gateway WebFlux / Spring Security | 4.3.5 / 6.5.11 |
| MyBatis-Plus Boot 3 Starter / Flyway / Connector/J | 3.5.18 / 11.7.2 / 9.7.0 |
| MinIO Java SDK / Lettuce / Spring AMQP | 8.6.0 / 6.6.0.RELEASE / 3.2.12 |

版本由根 POM 属性、Spring Boot Parent 和 Cloud BOM 管理。JWT 使用 Spring Security OAuth2 JOSE 的 Nimbus 实现，由 Boot 管理版本。矩阵和所选依赖坐标已核实，补丁组合通过本地运行验收。[Spring Cloud 兼容矩阵](https://spring.io/projects/spring-cloud/) · [Spring Cloud Alibaba 兼容矩阵](https://sca.aliyun.com/en/docs/2025.x/overview/version-explain/) · [Gateway WebFlux Starter](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webflux/starter.html)
