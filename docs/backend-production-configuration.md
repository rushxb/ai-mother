# 后端生产环境配置基线

后端配置按 `application.yml`（公共）、`application-dev.yml`（开发）和 `application-prod.yml`（生产）分层。生产环境不得提交真实密码、API Key、Cookie 或云服务密钥到 Git。

## 构建生产产物

生产构建必须使用 Maven `prod` Profile。构建过程会把产物内
`spring.profiles.active` 固定为 `prod`，避免生产 JAR 意外携带开发 Profile：

```powershell
$env:JAVA_HOME='D:\java\jdk21'
.\mvnw.cmd -Pprod clean package
java -jar target/rush-ai-code-mother-0.0.1-SNAPSHOT.jar
```

开发构建默认使用 Maven `dev` Profile。CI 和部署流水线不得省略 `-Pprod`。
生产构建必须先执行 `clean`，避免已删除或迁移的类残留在增量编译目录并被重新打入 JAR。
应用仍允许通过 `SPRING_PROFILES_ACTIVE` 使用 Spring Boot 标准外部配置覆盖活动
Profile，但生产部署不应覆盖回 `dev`。

容器或进程管理器中应通过 Secret、环境变量或挂载的外部配置文件注入敏感值，
不要把值直接写入启动脚本。

## 生产配置与覆盖变量

生产 Profile 提供可直接启动的 localhost 类非敏感默认值。部署到真实生产基础设施时，应按实际网络和账号覆盖下列变量：

| 变量 | 默认值与说明 |
| --- | --- |
| `MYSQL_URL` | 默认连接本机 `rush_ai_code_mother`；部署时建议包含 Unicode、时区和 TLS 参数 |
| `MYSQL_USERNAME` | 默认 `root`；部署时应改为数据库最小权限账号 |
| `MYSQL_PASSWORD` | 默认空；真实生产数据库密码必须通过 Secret、环境变量或外部配置注入 |
| `REDIS_HOST` | 默认 `localhost` |
| `REDIS_USERNAME` | 默认空；Redis ACL 启用用户名认证时配置，可使用 `root` 等部署侧账号 |
| `REDIS_PASSWORD` | 默认空；Redis 启用认证时必须通过安全配置注入 |
| `CORS_ALLOWED_ORIGINS` | 默认 `http://localhost:5173`；多个值用英文逗号分隔，禁止 `*` |
| `CODE_DEPLOY_HOST` | 默认 `http://localhost:91`；部署时覆盖为已部署应用的公开访问根地址 |

示例 Origin：

```text
https://console.example.com,https://admin.example.com
```

Origin 必须包含协议和主机，不得包含路径、查询参数、片段或通配符。

## 常用可选变量

- `SERVER_PORT`：服务端口，默认 `8123`。
- `SESSION_TIMEOUT`：服务端 Session 有效期，生产默认 `8h`。
- `SESSION_COOKIE_MAX_AGE`：浏览器 Cookie 有效期，生产默认 `8h`。
- `SESSION_COOKIE_SAME_SITE`：默认 `lax`；若确需跨站 Cookie，必须在 HTTPS 下评估后设置为 `none`。
- `REDIS_PORT`：默认 `6379`。
- `REDIS_DATABASE`：生产默认 `0`。
- `RATE_LIMIT_KEY_PREFIX`：分布式限流 Redis 键前缀，默认 `rate_limit`。
- `RATE_LIMIT_IDLE_TTL`：长期无请求的限流器键回收时间，默认 `1h`。
- `RATE_LIMIT_CONNECTION_MINIMUM_IDLE_SIZE`、`RATE_LIMIT_CONNECTION_POOL_SIZE`：限流专用 Redisson 单节点连接池参数，默认 `1` 和 `10`。
- `RATE_LIMIT_IDLE_CONNECTION_TIMEOUT`、`RATE_LIMIT_CONNECT_TIMEOUT`、`RATE_LIMIT_RESPONSE_TIMEOUT`：限流专用 Redis 连接超时，默认 `30s`、`5s`、`3s`。
- `RATE_LIMIT_RETRY_ATTEMPTS`、`RATE_LIMIT_RETRY_INTERVAL`：限流专用 Redis 重试参数，默认 `3` 次和 `1500ms`。
- `RATE_LIMIT_FORWARDED_HEADER_MAX_LENGTH`、`RATE_LIMIT_FORWARDED_FOR_MAX_HOPS`：转发头安全上限，默认 `4096` 字符和 `32` 跳。
- `APP_RATE_LIMITER_TRUSTED_PROXIES`：可信反向代理 CIDR 列表，逗号分隔；默认空，即忽略所有客户端转发头。
- `AI_LOG_REQUESTS`、`AI_LOG_RESPONSES`：生成模型请求和响应日志开关，默认关闭。
- `AI_ROUTING_LOG_REQUESTS`、`AI_ROUTING_LOG_RESPONSES`：路由模型日志开关，默认关闭。
- `AI_ROUTING_TIMEOUT`：路由模型超时，默认 `30s`，允许范围 `3s` 至 `5m`。
- `AI_ROUTING_MAX_RETRIES`：路由模型重试次数，默认 `0`，允许范围 `0` 至 `5`。
- `AI_CREATE_SPEC_TIMEOUT`：CREATE Spec 模型超时，默认 `10s`，允许范围 `3s` 至 `10s`。
- `REDIS_CACHE_DEFAULT_TTL`：Redis 业务缓存默认 TTL，默认 `30m`。
- `REDIS_CACHE_GOOD_APP_PAGE_TTL`：优质应用分页缓存 TTL，默认 `5m`。
- `COS_ENABLED`：启用 COS 时，还必须提供 `COS_HOST`、`COS_SECRET_ID`、`COS_SECRET_KEY`、`COS_REGION`、`COS_BUCKET`。
- `DEV_SERVER_CONNECT_TIMEOUT`、`DEV_SERVER_REQUEST_TIMEOUT`：开发服务器代理超时。
- `DEV_SERVER_MAX_REQUEST_BODY`、`DEV_SERVER_MAX_RESPONSE_BODY`：开发服务器代理请求和响应体上限。
- `DEV_SERVER_STARTUP_TIMEOUT`：Dev Server 启动就绪总超时，默认 `30s`。
- `DEV_SERVER_READINESS_POLL_INTERVAL`：回环端口就绪轮询间隔，默认 `200ms`，必须小于启动超时。
- `DEV_SERVER_VALIDATION_ERROR_COLLECTION_WINDOW`：就绪后收集首次编译延迟错误的时间窗口，默认 `5s`。
- `DEV_SERVER_OUTPUT_DRAIN_TIMEOUT`：进程退出后等待输出消费任务收口的时间，默认 `2s`。
- `DEV_SERVER_STOP_TIMEOUT`：停止启动中会话时等待补偿清理的上限，默认 `10s`。
- `DEV_SERVER_NODE_EXECUTABLE`：固定 Node.js 可执行文件名或绝对路径，默认 `node`。
- `DEV_SERVER_PORT_RANGE_START`、`DEV_SERVER_PORT_RANGE_END`：单实例 Dev Server 端口池，默认 `10000` 至 `60000`。
- `DEV_SERVER_MAX_SERVERS_PER_USER`：单实例内每个用户的同时会话上限，默认 `3`。
- `DEV_SERVER_MAX_OUTPUT_LINE_LENGTH`：单行进程输出字符上限，默认 `2000`。
- `DEV_SERVER_MAX_RECENT_OUTPUT_LINES`：单应用内存中保留的最近输出行数，默认 `200`。
- `DEPENDENCY_INSTALL_COMMAND_TIMEOUT`：单次 `pnpm install` 总超时，默认 `5m`。
- `DEPENDENCY_INSTALL_IDLE_TIMEOUT`：安装进程持续无输出超时，默认 `90s`。
- `DEPENDENCY_INSTALL_HEARTBEAT_INTERVAL`：安装过程心跳日志间隔，默认 `15s`，必须小于总超时。
- `DEPENDENCY_RUNTIME_VALIDATION_TIMEOUT`：Vite 运行时加载校验超时，默认 `30s`。
- `EXTERNAL_PROCESS_TERMINATION_GRACE_PERIOD`：终止外部进程树时的优雅退出等待时间，默认 `2s`。
- `DEPENDENCY_OUTPUT_DRAIN_TIMEOUT`：进程退出后等待输出消费线程收口的时间，默认 `2s`。
- `DEPENDENCY_INSTALL_MAX_ATTEMPTS`：单项目依赖安装最大尝试次数，默认 `3`，允许范围 `1` 至 `5`。
- `DEPENDENCY_INSTALL_MAX_OUTPUT_LENGTH`：内存中保留的安装输出字符上限，默认 `12000`。
- `DEPENDENCY_INSTALL_LOCK_STRIPES`：本地项目安装锁条带数，默认 `64`；仅用于单实例内并发隔离。
- `TEMPLATE_PRE_WARM_ENABLED`：模板依赖启动预热开关；开发默认开启，生产默认关闭。
- `TEMPLATE_PRE_WARM_MAX_CONCURRENCY`：模板预热专用线程池并发上限，默认 `2`，允许范围 `1` 至 `8`。

## 依赖安装运行要求

1. 生产节点必须安装 Java 21、受支持的 Node.js 与 pnpm，并确保后端运行账号的 `PATH` 可以解析 `node` 和 `pnpm`。
2. Node.js 与 pnpm 版本应由部署镜像或基础设施清单固定，不应在应用启动或请求处理中临时下载、自动升级。
3. 后端运行账号仅应拥有生成项目根目录及其子目录的读写权限，不应授予系统目录、其他租户目录或其他项目工作区的写权限。
4. 生成项目目录不得使用指向外部目录的符号链接；依赖完整性修复只允许在当前项目的 `node_modules/.pnpm` 范围内执行。
5. 单实例通过项目级条带锁避免同一项目并发安装。若生产环境允许多个应用实例共享同一生成目录，必须在基础设施层保证单写者，或增加分布式项目锁后再开放共享写入。
6. 应为依赖安装预留受控的 CPU、内存、磁盘和进程配额，并结合总超时、无输出超时与容器终止宽限期设置监控告警。
7. 生产环境默认不在应用启动阶段执行模板依赖安装。确需启用 `TEMPLATE_PRE_WARM_ENABLED=true` 时，必须固定 Node.js 与 pnpm 版本，并为预热线程池、外部进程、磁盘和网络访问配置明确配额。
8. 模板预热使用独立有界线程池，不占用应用通用异步执行器；模板列表由 `app.template-pre-warm.template-ids` 配置，模板 ID 必须是唯一的安全相对标识。

## Dev Server 运行要求

1. 生产镜像必须固定 Node.js 版本，项目依赖必须固定 Vite 版本；请求处理过程不会临时下载或升级工具。
2. 后端只使用系统固定的 Node.js 执行当前项目 `node_modules` 内的 `vite/bin/vite.js`，不使用 shell、`npx` 或 `pnpm run dev` 降级路径。
3. Dev Server 强制绑定 `127.0.0.1` 并启用 `--strictPort`，只能通过后端鉴权代理访问，不应直接暴露宿主机端口。
4. 端口保留和用户配额是单应用实例内状态。多实例共享同一宿主机网络命名空间时，必须由调度器或基础设施避免端口池冲突，并在集群层执行总配额。
5. 启动超时、取消、线程中断和服务关闭都会终止项目进程树并等待输出任务收口；应监控启动超时、强制终止和端口池耗尽指标。

## 安全约束

1. 生产 Session Cookie 强制启用 `Secure` 和 `HttpOnly`。
2. API 文档和 Knife4j 在生产 Profile 中默认关闭。
3. Actuator health 不返回组件内部详情；外部只暴露 `health`、`info`、`prometheus`。
4. CORS 使用显式 Origin 白名单，并在启动期拒绝通配符或格式错误的 Origin。
5. localhost、端口、空密码等非敏感默认值允许存在，以保证配置层可启动；部署系统必须按实际基础设施覆盖数据库、Redis、CORS 和部署域名。
6. AI 模型地址、模型名称与密钥只在数据库模型目录中维护，配置文件不保留第二套模型凭据。
7. 建议在网关层进一步限制 Actuator、管理接口和预览资源的访问来源。
8. 限流模块默认不信任 `X-Forwarded-For` 和 `X-Real-IP`。只有直接上游和代理链命中
   `APP_RATE_LIMITER_TRUSTED_PROXIES` 时才会解析转发头；代理必须覆盖客户端传入的同名请求头。
9. 生产默认 `SERVER_FORWARD_HEADERS_STRATEGY=none`，避免 Web 容器在可信代理校验前改写远端地址。
   如基础设施确需启用 `framework`，入口代理必须清洗转发头，并与限流可信代理列表保持一致。

## 本地私有覆盖

本地个性化配置使用 `src/main/resources/application-local.yml`，该文件已被 Git 忽略。启用方式：

```powershell
$env:SPRING_PROFILES_ACTIVE='dev,local'
.\mvnw.cmd spring-boot:run
```

不要修改并提交生产配置文件来保存个人账号或密钥。
