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

## 数据库迁移发布门禁

- 新数据库使用 `sql/create_table.sql` 初始化；既有数据库只执行 `sql/migrations` 中尚未应用的版本。
- 迁移必须在应用进程切换流量前完成，并使用独立迁移账号；应用运行账号不应持有日常 DDL 权限。
- 发布流水线必须记录迁移版本与文件校验值。已执行迁移不可原地修改，只能追加更高版本。
- 积分、生成追踪等完整性迁移遇到脏数据时应直接阻断发布，禁止静默修正或删除审计流水。
- 当前版本的迁移顺序和操作要求见 `sql/migrations/README.md`。

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
- `CHAT_MEMORY_KEY_PREFIX`：对话记忆 Redis 键命名空间，默认 `chat-memory:`。
- `CHAT_MEMORY_TTL_SECONDS`：Redis 对话记忆 TTL，默认 `3600` 秒；未配置时兼容读取旧变量 `REDIS_TTL`。
- `CHAT_MEMORY_FALLBACK_MAX_ENTRIES`：Redis 故障期间进程内回退状态总容量，默认 `1000`。容量不足时优先淘汰已同步副本；若容量全部被待回灌更新或删除占用，新变更会明确失败，不会返回成功后静默丢失。
- `CHAT_MEMORY_FALLBACK_EXPIRE_AFTER_ACCESS`：已同步进程内副本的访问过期时间，默认 `2h`；待回灌变更不参与过期淘汰，Redis 恢复后会优先回灌。
- `GENERATION_SESSION_LOCK_STRIPES`：单实例生成启动互斥的固定条带锁数量，默认 `64`，允许范围 `1` 至 `4096`；不同应用允许安全共享条带。
- `GENERATION_SESSION_MAX_TRACKED_SESSIONS`：单实例活动会话与短期回放会话总容量，默认 `1000`，允许范围 `1` 至 `10000`；达到上限后新应用生成请求明确返回服务暂不可用，不会继续扩张内存。
- `GENERATION_SESSION_COMPLETED_REPLAY_RETENTION`：已完成轻量会话的 SSE 回放保留时间，默认 `30s`，必须为正数且不得超过 `1h`。
- `GENERATION_SESSION_CLEANUP_INTERVAL`：过期回放会话批量清理周期，默认 `5s`，必须为正数且不得大于回放保留时间。
- `CODE_OUTPUT_ROOT_DIR`：AI 生成工作区根目录，默认 `tmp/code_output`。
- `CODE_DEPLOY_ROOT_DIR`：已部署应用视图根目录，默认 `tmp/code_deploy`。
- `CODE_SNAPSHOT_ROOT_DIR`：生成事务快照根目录，默认 `tmp/code_snapshot`；必须与生成工作区和部署根目录两两隔离，不得相同或互相包含。
- `GENERATION_TASK_SNAPSHOT_ENABLED`：本机编排诊断快照开关，默认 `true`；关闭后不影响数据库中的生成状态、租约和 trace。
- `GENERATION_TASK_SNAPSHOT_ROOT_DIRECTORY`：诊断快照根目录，默认继承 `code.orchestration-task-root-dir`，最终回退到 `tmp/orchestration_tasks`。
- `GENERATION_TASK_SNAPSHOT_MAX_BYTES`：单个快照 UTF-8 字节上限，默认 `2097152`（2 MiB），允许范围 `16384` 至 `16777216`。
- `GENERATION_TASK_SNAPSHOT_MAX_PER_APP`：单个应用最多保留的快照数，默认 `100`，允许范围 `1` 至 `1000`。
- `GENERATION_TASK_SNAPSHOT_RETENTION`：快照保留期限，默认 `7d`，必须为正数。
- `GENERATION_TASK_SNAPSHOT_LOCK_STRIPES`：单实例本地写锁条带数，默认 `64`，允许范围 `1` 至 `1024`。
- `EDIT_STATE_ENABLED`：连续改修编辑状态的本地磁盘持久化开关，默认 `true`；关闭后仍保留有界的进程内文件召回缓存。
- `EDIT_STATE_ROOT_DIRECTORY`：编辑状态根目录，默认继承 `code.edit-state-root-dir`，最终回退到 `tmp/edit_state`。
- `EDIT_STATE_MAX_CACHE_ENTRIES`：单实例缓存的最大应用数，默认 `1000`，允许范围 `1` 至 `100000`。
- `EDIT_STATE_CACHE_EXPIRE_AFTER_ACCESS`：缓存访问过期时间，默认 `2h`，必须为正数且不得超过 `EDIT_STATE_RETENTION`。
- `EDIT_STATE_RETENTION`：本地状态文件保留期限，默认 `24h`，必须为正数。
- `EDIT_STATE_MAX_PERSISTED_APPS`：本地最多保留状态文件的应用数，默认 `10000`，允许范围 `1` 至 `100000`。
- `EDIT_STATE_MAX_FILE_BYTES`：单状态文件字节上限，默认 `1048576`（1 MiB），允许范围 `16384` 至 `8388608`。
- `EDIT_STATE_MAX_RECENT_EDITS`：单应用最近编辑记录上限，默认 `20`，允许范围 `1` 至 `200`。
- `EDIT_STATE_MAX_RECENT_FILES`：单应用最近文件上限，默认 `50`，允许范围 `1` 至 `1000`。
- `EDIT_STATE_MAX_RECENT_VALIDATIONS`：单应用最近验证状态上限，默认 `20`，允许范围 `1` 至 `200`。
- `EDIT_STATE_MAX_TASK_ID_LENGTH`：任务标识最大长度，默认 `128`，允许范围 `16` 至 `256`。
- `EDIT_STATE_MAX_FILE_PATH_LENGTH`：相对文件路径最大长度，默认 `1024`，允许范围 `128` 至 `4096`。
- `EDIT_STATE_LOCK_STRIPES`：单实例同应用更新锁条带数，默认 `64`，允许范围 `1` 至 `1024`。
- `COS_ENABLED`：启用 COS 时，还必须提供 `COS_HOST`、`COS_SECRET_ID`、`COS_SECRET_KEY`、`COS_REGION`、`COS_BUCKET`。
- `DEV_SERVER_CONNECT_TIMEOUT`、`DEV_SERVER_REQUEST_TIMEOUT`：开发服务器代理超时。
- `DEV_SERVER_MAX_REQUEST_BODY`、`DEV_SERVER_MAX_RESPONSE_BODY`：开发服务器代理请求和响应体上限。
- `DEV_SERVER_STARTUP_TIMEOUT`：Dev Server 启动就绪总超时，默认 `30s`。
- `DEV_SERVER_READINESS_POLL_INTERVAL`：回环端口就绪轮询间隔，默认 `200ms`，必须小于启动超时。
- `DEV_SERVER_VALIDATION_ERROR_COLLECTION_WINDOW`：就绪后收集首次编译延迟错误的时间窗口，默认 `5s`。
- `DEV_SERVER_OUTPUT_DRAIN_TIMEOUT`：进程退出后等待输出消费任务收口的时间，默认 `2s`。
- `DEV_SERVER_STOP_TIMEOUT`：停止启动中会话时等待补偿清理的上限，默认 `10s`。
- `NODE_TOOLCHAIN_NODE_EXECUTABLE`：所有受控 Node.js 子进程共用的可执行文件名或绝对路径，默认 `node`；Windows 默认解析为 `node.exe`。
- `NODE_TOOLCHAIN_PNPM_EXECUTABLE`：所有受控 pnpm 子进程共用的可执行文件名或绝对路径，默认 `pnpm`；Windows 默认解析为 `pnpm.cmd`。
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
- `ARTIFACT_COPY_TIMEOUT`: hard timeout for one Windows robocopy operation, default `15m`.
- `ARTIFACT_COPY_HEARTBEAT_INTERVAL`: robocopy heartbeat interval, default `30s`; it must be shorter than the copy timeout.
- `ARTIFACT_COPY_OUTPUT_DRAIN_TIMEOUT`: bounded wait for process-output consumers after robocopy exits, default `5s`.
- `ARTIFACT_COPY_MAX_OUTPUT_LENGTH`: maximum retained characters for each robocopy output stream, default `8000`.
- `ARTIFACT_COPY_MAX_FILES`: maximum files in one generated-source or deployment artifact copy, default `20000`.
- `ARTIFACT_COPY_MAX_DIRECTORIES`: maximum directories in one artifact copy excluding the source root, default `5000`.
- `ARTIFACT_COPY_MAX_DIRECTORY_DEPTH`: maximum directory depth relative to the source root, default `64`.
- `ARTIFACT_COPY_MAX_FILE_BYTES`: maximum size of one artifact file, default `104857600` bytes (100 MiB).
- `ARTIFACT_COPY_MAX_TOTAL_BYTES`: maximum cumulative file bytes in one artifact copy, default `2147483648` bytes (2 GiB); it must be greater than or equal to the single-file limit.
- `ARTIFACT_PUBLISH_MAX_ATTEMPTS`: maximum attempts for an artifact directory publish, deployment switch, rollback restore, or quarantine move after a transient `AccessDeniedException`; default `5`, allowed range `1` to `20`.
- `ARTIFACT_PUBLISH_RETRY_DELAY_MILLIS`: retry delay in milliseconds after a transient artifact directory access denial; default `50`, allowed range `0` to `5000`. A destination that appears during the retry window is never overwritten.
- Artifact copies reject every symbolic link and unsupported special file. The source is inspected before and after copying, and the staged target is independently checked before activation.
- `WORKSPACE_MAX_FILES`：单次工作区扫描、快照复制或在线文件树允许处理的最大文件数，默认 `20000`。
- `WORKSPACE_MAX_DIRECTORY_DEPTH`：工作区扫描和快照复制允许进入的最大目录深度，默认 `64`。
- `WORKSPACE_MAX_SCANNED_BYTES`：单次只读工作区扫描允许累计索引的最大字节数，默认 `2147483648`（2 GiB）。
- `WORKSPACE_MAX_FILE_BYTES`：快照复制允许处理的单文件最大字节数，默认 `104857600`（100 MiB）。
- `WORKSPACE_MAX_READABLE_FILE_BYTES`：语义索引、代码图和差异摘要允许读取的单文件最大字节数，默认 `2097152`（2 MiB）。
- `WORKSPACE_MAX_INTERACTIVE_FILE_BYTES`：在线预览和编辑允许读取或写入的单文件最大字节数，默认 `1048576`（1 MiB）。
- `WORKSPACE_MAX_INTERACTIVE_TREE_DEPTH`：在线应用代码文件树允许展示的最大目录深度，默认 `8`。
- `WORKSPACE_MAX_COPY_BYTES`：单次事务型目录复制允许写入的最大总字节数，默认 `2147483648`（2 GiB）。
- `WORKSPACE_MAX_PERSISTED_FILE_BYTES`：工作区服务原子持久化单文件的最大字节数，默认 `67108864`（64 MiB）。
- `WORKSPACE_MAX_LISTED_DIRECTORIES`：单次快照目录列表或在线文件树允许返回的最大目录数，默认 `1000`。
- `WORKSPACE_PUBLISH_MAX_ATTEMPTS`：快照发布、工作区恢复切换或失败回切遇到瞬时 `AccessDeniedException` 时的最大尝试次数，默认 `5`，允许范围 `1`～`20`。
- `WORKSPACE_PUBLISH_RETRY_DELAY_MILLIS`：工作区目录发布重试间隔（毫秒），默认 `50`，允许范围 `0`～`5000`。仅访问拒绝会重试；普通 I/O 错误立即失败，重试期间出现的目标目录不会被覆盖。
- `GENERATION_CONTEXT_MAX_PROJECT_INDEX_FILES`：生成提示词中的项目索引最多包含的文件数，默认 `80`。
- `GENERATION_CONTEXT_MAX_SINGLE_FILE_CHARS`：单个关键项目文件可进入生成上下文的最大字符数，默认 `12000`。
- `GENERATION_CONTEXT_MAX_TOTAL_CONTEXT_CHARS`：单次生成项目上下文的总字符预算，默认 `100000`，不得小于单文件字符预算。
- `GENERATION_CONTEXT_MAX_READABLE_FILE_BYTES`：生成上下文允许读取的单个关键文件最大字节数，默认 `1048576`（1 MiB）。
- `PROJECT_COMMAND_SNAPSHOT_MAX_FILES`、`PROJECT_COMMAND_SNAPSHOT_MAX_FILE_BYTES`：仅作为旧部署的兼容回退变量保留；新部署应分别改用 `WORKSPACE_MAX_FILES`、`WORKSPACE_MAX_FILE_BYTES`。
- `PROJECT_COMMAND_RECENT_BUILD_RESULT_MAX_ENTRIES`：内存中最多保留的最近 Vue 构建结果数量。，默认 `500`。
- `EDIT_LOCATOR_MAX_CANDIDATE_FILES`: maximum ordered edit-file candidates, default `8`.
- `EDIT_LOCATOR_MAX_SINGLE_FILE_CHARS`: per-file edit-context character budget, default `20480`.
- `EDIT_LOCATOR_MAX_TOTAL_CONTEXT_CHARS`: total edit-context character budget, default `61440`.
- `EDIT_LOCATOR_MAX_SCANNED_FILES`: maximum files visited by one workspace scan, default `20000`.
- `EDIT_LOCATOR_MAX_READABLE_FILE_BYTES`: maximum source file size accepted for context reads, default `2097152` bytes.
- `EDIT_LOCATOR_MAX_PROJECT_INDEX_FILES`: maximum entries in the compact project index, default `80`.
- `PATCH_MAX_OPERATIONS`：单批本地补丁允许的最大操作数，默认 `100`。
- `PATCH_MAX_OPERATION_CONTENT_CHARS`：单个补丁操作携带的内容字符总量上限，默认 `1000000`。
- `PATCH_MAX_TOTAL_CONTENT_CHARS`：单批补丁携带的内容字符总量上限，默认 `5000000`。
- `PATCH_MAX_READABLE_FILE_BYTES`：补丁校验、执行及编辑后后端轻量校验允许读取的单文件最大字节数，默认 `5242880`。
- `PATCH_MAX_WRITTEN_FILE_BYTES`：补丁落盘后单文件 UTF-8 最大字节数，默认 `10485760`。
- `PATCH_MAX_ROLLBACK_SNAPSHOT_BYTES`：单批补丁执行前用于失败回滚的内存快照总字节上限，默认 `20971520`；不得小于单文件读取上限。
- `AI_TOOL_MAX_READABLE_FILE_BYTES`：AI 文件工具单文件读取上限，默认 `1048576`；实际读取上限不会超过 `PATCH_MAX_READABLE_FILE_BYTES`。
- `AI_TOOL_MAX_DIRECTORY_ENTRIES`：AI 目录工具单次最多返回的文件和目录条目数，默认 `5000`。
- `AI_TOOL_MAX_DIRECTORY_DEPTH`：AI 目录工具单次遍历的最大相对深度，默认 `32`；遍历不跟随符号链接。
- `GENERATION_COMMIT_MAX_FILES_PER_COMMIT`：单次生成提交允许处理的去重文件数上限，默认 `20000`，允许范围 `1` 至 `100000`；超限时在执行 Git 写操作前拒绝提交。
- `GENERATION_COMMIT_MAX_PATHSPEC_BYTES`：单次生成提交的 NUL 分隔 Git pathspec 文件 UTF-8 字节上限，默认 `2097152`（2 MiB），允许范围 `4096` 至 `16777216`；用于避免 Windows 命令行长度上限和无界临时文件。
- Node.js 工具链通过参数列表直接启动，不经过 shell、`npx` 或 Corepack 在线降级；生产镜像应固定 Node.js 与 pnpm 版本，并优先配置绝对路径。
- 受控 Node.js 子进程不会继承 `NODE_OPTIONS`、`NODE_PATH`、`NPM_CONFIG_PREFIX`、`PNPM_HOME` 及其小写变体，避免宿主环境注入额外启动参数或改变依赖解析路径。
- `TEMPLATE_MATERIALIZATION_MAX_FILES`：单次模板物化允许复制的最大文件数，默认 `2000`。
- `TEMPLATE_MATERIALIZATION_MAX_FILE_BYTES`：单个模板资源允许复制的最大字节数，默认 `10485760`（10 MiB）。
- `TEMPLATE_MATERIALIZATION_MAX_TOTAL_BYTES`：单次模板物化允许复制的累计最大字节数，默认 `104857600`（100 MiB），且不得小于单文件上限。
- `TEMPLATE_MATERIALIZATION_MAX_RELATIVE_PATH_LENGTH`：模板资源规范化相对路径的最大长度，默认 `1024`。
- `TEMPLATE_MATERIALIZATION_MAX_DIRECTORY_DEPTH`：模板资源相对目录的最大深度，默认 `32`。
- `TEMPLATE_MATERIALIZATION_PUBLISH_MAX_ATTEMPTS`：模板目录发生瞬时 `AccessDeniedException` 时的最大发布尝试次数，默认 `5`；目标已经出现时不会继续重试，而是重新执行安全类型校验。
- `TEMPLATE_MATERIALIZATION_PUBLISH_RETRY_DELAY_MILLIS`：模板目录发布重试间隔，默认 `50` 毫秒。
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
8. 模板 Bootstrap 只接受应用内置模板清单中的固定标识；资源先在目标目录同级 staging 目录中受限物化，全部复制与定制成功后才发布，失败和并发竞争均不得暴露半成品工作区。
9. 模板预热使用独立有界线程池，不占用应用通用异步执行器；`app.template-pre-warm.template-ids` 只能选择内置 Node.js 模板。预热缓存失效或不安全时应回退为普通模板物化，而不能阻断项目创建。
10. 模板物化与预热依赖复制统一拒绝绝对路径、路径穿越、重复资源路径、符号链接、普通文件冒充目录和并发替换，并受文件数、单文件大小、累计字节数、相对路径长度及目录深度限制。

## 生成状态所有权与租约

1. 应用表通过 `generatingTaskId` 和 `generationLeaseUntil` 保存当前生成任务所有权。
2. 启动、阶段更新、流式快照、代码类型回滚和终态释放都必须携带同一个 taskId；旧任务不能覆盖新任务状态。
3. 租约时长为 `GENERATION_TASK_TIMEOUT` 加一分钟安全余量。进程异常退出后，租约过期的新任务可以原子接管状态。
4. 应用状态、生成 trace 和积分结算通过业务事务边界提交；任一步失败都会回滚数据库状态。
5. 部署本版本前必须执行 `V20260714_4__app_generation_state_ownership.sql`。迁移会清理旧版本遗留的无所有者生成标记。
6. 数据库租约只解决状态所有权；多实例共享同一生成目录时，仍必须遵守单写者或分布式项目锁要求。

## 生成会话资源边界

1. 同一应用的生成启动互斥使用固定数量的条带锁。锁对象数量只由 `GENERATION_SESSION_LOCK_STRIPES` 决定，不随历史应用 ID 数量增长。
2. 活动会话和轻量流程完成后的短期 SSE 回放会话共享 `GENERATION_SESSION_MAX_TRACKED_SESSIONS` 容量。容量满载时拒绝新应用会话，避免通过大量不同应用 ID 持续扩大堆内存。
3. 已完成会话不会创建独立的 `CompletableFuture` 延迟任务；注册表只记录回放过期时间，由一个受 Spring 生命周期管理的固定周期任务批量清理。
4. 会话替换、比较删除和过期清理都校验会话对象身份，旧会话的清理不能删除同一应用后来创建的新会话。
5. 该注册表是单实例内存状态。多实例部署必须继续依赖数据库生成所有权与租约避免跨实例并发生成，容量指标也应按实例监控。

## 编排诊断快照运行要求

1. 编排诊断快照是节点本地、尽力而为的故障诊断数据，不参与任务成功判定；应用生成所有权、租约和 trace 以数据库状态为准。
2. 快照只持久化请求的 SHA-256 指纹，不保存原始用户请求；写入使用同目录临时文件、文件强制刷盘和原子替换，避免进程中断留下半文件。
3. 单文件大小、单应用数量和保留期限必须保持有界。快照超限时会拒绝新内容并删除同任务旧快照，避免旧诊断状态被误认为最新状态。
4. 运行账号只应拥有配置快照根目录的读写权限，应用目录和快照文件不得通过符号链接逃逸到根目录之外。
5. 多实例部署默认使用节点本地独立目录。只有共享存储已经提供单写者语义并完成并发验证时，才允许多个实例共享同一快照目录。

## 连续改修编辑状态运行要求

1. 编辑状态只用于当前节点连续改修时的文件召回，不是应用内容、生成任务或验证结果的权威数据源。
2. 持久化模型只包含安全任务标识、规范化相对文件路径、成功状态和时间戳；原始用户消息、补丁失败原因、验证消息及详情不会写入本地文件。
3. 同一应用的读取、不可变快照更新、原子保存和缓存替换由同一条带锁保护；不同应用可并行更新。磁盘写入使用同目录临时文件、强制刷盘和原子替换。
4. 缓存容量、缓存过期时间、文件大小、状态文件总数、单应用记录数和磁盘保留期限均必须保持有界。超大新状态不会覆盖上一份有效文件；损坏、过期或旧 schema 文件会被删除，避免反复解析和继续保留历史敏感字段。
5. 多实例部署默认使用节点本地独立目录。共享目录不会提供跨实例写入协调，除非底层存储和部署架构已经保证单写者语义，否则禁止多个实例共享。
6. 旧版本写入用户主目录 `.ai-code-edit-state` 的文件不再加载。确认不需要回滚旧版本后，应由部署清理流程删除该历史目录。

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
   - `/api/actuator/health/liveness` 只判断应用进程是否存活，不依赖 MySQL、Redis；失败时应重启实例。
   - `/api/actuator/health/readiness` 同时检查应用就绪状态、MySQL 和 Redis；失败时应停止接收流量，但不应仅因此反复重启实例。
   - `/api/health` 仅为历史兼容的进程存活接口，不能用于判断外部依赖是否可用。
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
