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

生产 Profile 不提供数据库、Redis、CORS 和部署地址的开发兜底值。部署系统必须通过 Secret、环境变量或外部配置显式注入下列变量；缺失、空白或命中不安全策略时，应用会在创建 Bean 前拒绝启动：

| 变量 | 默认值与说明 |
| --- | --- |
| `MYSQL_URL` | 生产必填；建议包含 Unicode、时区和 TLS 参数 |
| `MYSQL_USERNAME` | 生产必填；必须使用最小权限账号，禁止 `root` |
| `MYSQL_PASSWORD` | 生产必填；必须通过 Secret 注入，至少 16 个字符且不得使用常见默认值 |
| `REDIS_HOST` | 生产必填 |
| `REDIS_USERNAME` | 默认空；Redis ACL 启用用户名认证时配置，可使用 `root` 等部署侧账号 |
| `REDIS_PASSWORD` | 生产必填；必须通过 Secret 注入，至少 16 个字符且不得使用常见默认值 |
| `GENERATION_EVENT_STREAM_TRANSPORT` | 在线生产节点强制为 `redis`；独立 `benchmark-worker` profile 强制为 `local`，避免候选任务事件进入线上流 |
| `GENERATION_EVENT_STREAM_DELTA_COALESCING_ENABLED` | 默认 `true`；仅合并 Redis 共享事件流中的相邻 AI 文本增量，可作为发布回退开关 |
| `GENERATION_EVENT_STREAM_DELTA_FLUSH_INTERVAL` | 默认 `40ms`，允许 `10ms` 至 `1s`；控制首段之后文本增量的最大传输等待窗口 |
| `GENERATION_EVENT_STREAM_DELTA_MAX_CHARS` | 默认 `2048`，允许 `64` 至 `65536`；缓冲达到上限时立即写入 Redis |
| `GENERATION_TASK_QUEUE_TRANSPORT` | 在线生产节点强制为 `redis`；独立 `benchmark-worker` profile 强制为 `local`，避免 Worker 消费线上任务 |
| `BACKGROUND_JOBS_ENABLED` | 在线生产节点强制为 `true`；独立 Benchmark Worker profile 固定为 `false` |
| `GENERATION_BENCHMARK_EVIDENCE_SIGNING_SECRET` | 生产必填；隔离 Benchmark 评测器与发布控制面的 HMAC-SHA256 共享密钥，至少 32 个字符，只能通过 Secret 注入 |
| `GENERATION_BENCHMARK_GRADER_FINGERPRINT` | 当前评分器协议固定为 `generation-benchmark-graders-v6`；只有评分代码、数据集和证据消费者同步升级时才能变更 |
| `GENERATION_BENCHMARK_WORKER_OUTPUT_FILE` | Worker 启用时必填；必须指向运行账号可原子替换的结果文件路径 |
| `GENERATION_BENCHMARK_WORKER_EVIDENCE_VALIDITY` | 默认 `1d`，最大 `7d`，且不得超过控制面允许的最大证据有效期 |
| `GENERATION_BENCHMARK_WORKER_CANDIDATE_TYPE` | Worker 启用时必填；只允许 `AI_MODEL_ENABLE` 或 `PROMPT_RELEASE` |
| `GENERATION_BENCHMARK_WORKER_MODEL_ID` | 模型启用候选必填的正整数编号；Prompt 候选必须为 `0` |
| `GENERATION_BENCHMARK_WORKER_PROMPT_KEY` | Prompt 发布候选必填 |
| `GENERATION_BENCHMARK_WORKER_STABLE_VERSION` | Prompt 发布候选必填 |
| `GENERATION_BENCHMARK_WORKER_CANARY_VERSION` | 灰度比例大于 `0` 时必填；比例为 `0` 时必须为空 |
| `GENERATION_BENCHMARK_WORKER_CANARY_PERCENTAGE` | 默认 `0`，允许 `0` 到 `100` |
| `CORS_ALLOWED_ORIGINS` | 生产必填；多个值用英文逗号分隔，只允许无通配符、非 loopback 的 HTTPS Origin |
| `CODE_DEPLOY_HOST` | 生产必填；必须是非 localhost/loopback 的部署访问根地址 |
| `MILVUS_MEMORY_ENABLED` | 生产强制为 `true`；长期记忆和 durable memory outbox 不允许退化为仅进程内状态 |
| `MILVUS_URI` | 生产必填的 Milvus SDK HTTPS/gRPC endpoint；禁止 user-info、query、fragment、额外 path、localhost 和明文 HTTP |
| `MILVUS_TOKEN` | 生产必填；通过 Secret 注入并满足生产 secret 强度策略，禁止写入 URI、仓库或日志 |
| `MILVUS_AUTHENTICATION_REQUIRED` | 生产强制为 `true` |
| `MILVUS_TLS_REQUIRED` | 生产强制为 `true` |
| `MILVUS_VERIFY_ON_STARTUP` | 生产强制为 `true`；collection/schema/index/load 不兼容时阻断启动 |
| `GENERATION_MEMORY_OUTBOX_ENABLED` | 生产强制为 `true`；保障生成记忆写入与应用删除可重试、可恢复 |
| `OTEL_TRACING_ENABLED` | 默认 `true`；生产环境必须保持启用，以延续 HTTP、任务队列、模型、工具、构建和受控进程链路 |
| `OTEL_TRACING_EXPORT_ENABLED` | 开发默认 `false`、生产默认 `true`；生产环境必须保持启用 |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | 生产必填的 OTLP/HTTP traces 地址，例如 `http://otel-collector:4318/v1/traces` |

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
- `AI_FAILOVER_MAX_CANDIDATES`：单次请求最多参与故障切换的模型候选数，默认 `2`，允许范围 `1` 至 `5`；用于限制串行上游超时放大。
- `AI_LOCAL_FIRST_HEAVY_ROUTING_ENABLED`：HEAVY 目标工程类型的本地优先开关，默认 `true`。明确后端、全栈、Vue 或静态工程意图直接使用与应用创建阶段一致的确定性规则；没有类型变更证据时保留当前类型；只有前后端混合的模糊意图才调用路由模型。已有前端增加后端或已有后端增加前端时必须升级为全栈，禁止覆盖现有一侧能力。关闭开关可恢复为所有 HEAVY 请求调用类型路由模型。命中来源分别记录为 `heavy_intent_routing_local` 和 `heavy_intent_routing_model` span，发布前应比较模型调用率、目标类型准确率和首 Token 延迟。
- 生成性能档位只读取 Planner 基于原始用户需求写入 `requirements.complex` 的结构化结论，禁止再从包含模板、上下文和工程规范的增强 Prompt 推断复杂度，避免简单任务被固定术语误判为复杂任务。旧检查点或不完整 artifact 缺少该字段时必须回退到质量优先档。`ReasoningPolicy` 事件通过 `complexRequest`、`complexitySource`、`thinkingMode` 和 `modelTier` 记录实际决策；发布前必须同时比较各复杂度分组的首 Token、端到端耗时、构建通过率、Runtime/Visual 通过率和修复率。
- `AI_FIRST_SIGNAL_TIMEOUT`：模型尚未产生文本、思考、工具调用或其他有效流事件时的硬等待上限，默认 `45s`，允许范围 `3s` 至 `2m`，且不得超过 `AI_GENERATION_TIMEOUT`。触发后取消当前上游，并且只有在尚无模型事件和工作区副作用时才允许现有根模型策略重试；首个信号到达后该短窗口立即失效，后续仍由整回合墙钟和流空闲超时保护。该参数会进入发布配置指纹，调整前必须按模型与复杂度分组比较超时率、首 Token p95/p99、根重试率和最终质量门禁。
- 物理模型监督超时通过 `ai_model_timeouts_total{provider,model_name,timeout_kind}` 观测；`timeout_kind` 只允许 `first-signal`、`inactivity`、`wall-clock`。告警必须按供应商、模型和类型分别聚合，避免把首包延迟、流中断与持续输出超时误判为同一故障。
- `AI_FIRST_TOKEN_HEDGE_ENABLED`：是否为任务级限时流式请求启用首 Token 对冲，默认 `false`。开启后仅在首个有效流事件到达前最多启动一个影子候选，普通聊天和同步模型不受影响。
- `AI_FIRST_TOKEN_HEDGE_DELAY`：主候选无输出时启动影子候选前的等待时间，默认 `3s`，允许范围 `250ms` 至 `30s`。当延迟不小于当前请求的单候选超时片时，该次请求自动退回串行故障切换，避免放大任务截止时间。
- `AI_FIRST_TOKEN_HEDGE_REQUIRE_DISTINCT_PROVIDER`：是否要求主候选与影子候选来自不同供应商，默认 `true`，用于降低同一供应商区域性抖动导致两路同时变慢的风险。
- `AI_ROOT_MODEL_RETRY_MIN_DELAY`、`AI_ROOT_MODEL_RETRY_MAX_DELAY`：根模型在零可见输出的瞬态失败后刷新健康模型池前采用的指数退避边界，默认 `3s` 和 `20s`；允许范围为 `100ms` 至 `1m`，且最大值不得小于最小值。退避会按任务剩余 deadline 自动裁剪；若等待后无法保留 `minimum-operation-timeout`，则拒绝重试。
- `AI_ROOT_MODEL_RETRY_JITTER`：根模型退避抖动比例，默认 `0.35`，允许范围 `0.0` 至 `1.0`。根重试不会在已有模型事件或工具副作用后发生，provider 池内故障转移也不会额外触发该退避。
- 根模型链路通过 `ai_model_root_attempts_total`、`ai_model_root_attempt_duration_seconds`、`ai_model_root_retries_total`、`ai_model_root_retry_delay_seconds` 记录有界标签指标；任务 trace 额外记录 `root_model_attempt` 和 `root_model_retry_backoff` span，用于区分模型耗时与退避等待。
- `GENERATION_BENCHMARK_MAXIMUM_P90_FIRST_TOKEN_LATENCY`、`GENERATION_BENCHMARK_MAXIMUM_P99_FIRST_TOKEN_LATENCY`：发布 Benchmark 的首 Token p90/p99 上限，默认分别为 `15s`、`30s`；p99 上限不得低于 p90。报告 schema v4 会同时签入两项尾延迟，旧 schema 证据不能用于发布。
- `GENERATION_<ROUTE>_FIRST_PREVIEW_TIMEOUT`：从任务提交时间计算的首预览 SLA，`<ROUTE>` 可取 `CREATE`、`LIGHT_EDIT`、`AGENT_EDIT`、`HEAVY`、`SATURATED`，默认分别为 `60s`、`90s`、`3m`、`5m`、`2m`。它是首预览质量目标，不是任务总 deadline。首预览只在执行工作区完成原子发布且应用元数据提交成功后打点；后端工程记录可用构建产物，Vue/全栈工程记录可运行预览，构建或运行时验证通过但尚未发布不计入 SLA 成功。
- `GENERATION_<ROUTE>_FIRST_PREVIEW_COMPLETION_RESERVE`：首预览 SLA 内为模板渲染、校验、工作区写入和预览发布保留的确定性完成窗口，五个路由默认分别为 `45s`、`30s`、`45s`、`60s`、`30s`。预留必须为正，并至少给可选操作留下 `minimum-operation-timeout`；配置关系非法时应用拒绝启动。进入预留窗口后，CREATE 规格等可选 AI 增强会被跳过或取消并切换本地确定性路径，任务仍受总 deadline 控制并继续完成交付。
- `GENERATION_FIRST_PREVIEW_COMPLETION_RESERVE`：未携带路由 SLA 信封的兼容执行路径预留，默认 `10s`。新任务会把路由预留持久化到 durable command；滚动升级期间恢复缺少该字段的旧命令时优先回退到 `minimum-operation-timeout`，历史窗口不足时收缩到仍可保留一个最小操作窗口的最大安全值，避免反序列化失败或改变旧任务总 deadline。
- `GENERATION_STAGE_MODEL_TURN_MINIMUM`：启动一个新 Agent 模型回合必须拥有的有效模型执行时间，默认 `30s`。它不是 provider 超时；实际单回合超时仍取路由 `model-call-timeout`、任务剩余时间和受保护完成窗口三者的最小值。
- `GENERATION_STAGE_MODEL_HANDOFF_RESERVE`：模型结束到构建阶段之间保留的调度与回调交接余量，默认 `2s`。与 `GENERATION_STAGE_BUILD_MINIMUM=45s`、`GENERATION_STAGE_RUNTIME_VALIDATION_MINIMUM=15s`、`GENERATION_STAGE_TERMINALIZATION_RESERVE=10s` 组合后，HTML/原生多文件、后端、Vue/全栈的完成窗口分别为 `12s`、`57s`、`72s`，因此启动新回合所需的最小剩余时间分别为 `42s`、`87s`、`102s`。
- 所有路由 SLA、饱和降级 SLA 和旧任务兼容 SLA 都必须满足上述最严格窗口。任一路由 `total-timeout < 102s`，或 `model-call-timeout < 30s` 时，应用会在接流量前拒绝启动；修改任一阶段时长后该下限会自动重新计算，禁止只放宽总 deadline 而遗漏构建、Runtime 或终态窗口。
- 模型窗口耗尽只在本次尝试已经产生结构化成功工作区变更时转入工程校验。成功证据由补丁网关在 `PatchApplyResult.status=applied` 后写入执行上下文并随人在回路检查点持久化；模型文本、工具开始事件、预扣 `TOOL_WRITE`、补丁拒绝字符串或异常工具结果都不能触发 `codegen_done`。没有实际落盘时任务保持失败，禁止把模板或半成品伪装成交付物。
- 完成窗口停止通过 `generation_stage_admission_total{stage="model_turn",outcome="reserved_completion"}`、`model_turn_admission_reserved` span 和 `agent_event(status=reserved_completion,reason=completion_window_reserved)` 联合观测。该计数持续升高时应先检查模型回合数、单回合输出量和路由 SLA，不得直接缩短构建、Runtime、Visual 或安全门禁。
- `GENERATION_STREAM_SNAPSHOT_UPDATE_INTERVAL`、`GENERATION_STREAM_SNAPSHOT_MAX_CHARS`：重型生成数据库断线快照的最小写入间隔和尾部字符上限，默认 `5s`、`20000`，允许范围分别为 `100ms` 至 `1m`、`1` 至 `100000`。实时事件必须先进入会话事件流，不能被同步数据库写入阻塞；首个模型事件不会触发快照写入。快照不是任务恢复事实源，写入结果通过 `generation_stream_snapshot_writes_total` 和 `generation_stream_snapshot_write_duration_seconds` 监控。
- `GENERATION_EVENT_STREAM_DELTA_COALESCING_ENABLED`、`GENERATION_EVENT_STREAM_DELTA_FLUSH_INTERVAL`、`GENERATION_EVENT_STREAM_DELTA_MAX_CHARS`：生产 Redis 共享事件流默认合并相邻且不带结构化数据的 `AI_DELTA`，默认配置为 `true`、`40ms`、`2048`。第一段文本立即写入；工具、阶段、错误、思考、完成和应用关闭均为强制顺序边界。异步冲刷失败保留原缓冲并最多重试 3 次，重试耗尽后等待下一顺序边界再次冲刷。容量达到 `max-tracked-tasks` 时退化为直接写入，不丢弃事件。该策略不改变本机会话、trace、短期记忆或最终交付文件；Redis 原子追加、耗时、输入去向、冲刷结果和每批事件/字符数通过 `generation_event_stream_redis_appends_total`、`generation_event_stream_redis_append_duration_seconds`、`generation_event_stream_delta_inputs_total`、`generation_event_stream_delta_flushes_total`、`generation_event_stream_delta_events_per_flush`、`generation_event_stream_delta_chars_per_flush` 监控。
- `GENERATION_<ROUTE>_MAX_ROOT_MODEL_ATTEMPTS`：任务级独立根模型调用预算，`<ROUTE>` 可取 `CREATE`、`LIGHT_EDIT`、`AGENT_EDIT`、`HEAVY`、`SATURATED`，默认分别为 `4`、`2`、`2`、`4`、`2`，允许范围 `1` 至 `10`。CREATE 的规格模型、模糊意图回退后的重型类型路由、首次主生成及每轮修复各占一个调用；HEAVY 仅在本地规则无法确定目标类型时由类型路由占用一次，首次主生成及每轮修复各占一个调用。预算仍必须覆盖最坏合法拓扑，不能因大部分请求命中本地路由而缩小上限。正常工具回合和单个 provider 故障转移不消耗该预算。
- `GENERATION_<ROUTE>_MAX_MODEL_TURNS`：一次任务允许的正常 Agent 模型对话回合，五个路由默认分别为 `18`、`4`、`12`、`24`、`8`，允许范围 `1` 至 `100`。达到上限后必须终止循环，不能通过根重试重新获得回合。
- `GENERATION_<ROUTE>_MAX_PROVIDER_FAILOVER_ATTEMPTS`：首选 provider 请求失败后允许发起的额外候选请求数，五个路由默认分别为 `4`、`2`、`4`、`6`、`2`，允许范围 `1` 至 `100`。首个候选不消耗该预算，实际候选数量仍受 `AI_FAILOVER_MAX_CANDIDATES` 限制。
- `GENERATION_<ROUTE>_MAX_TOOL_WRITES`：任务允许尝试的文件变更操作预算，而不是成功文件数或工具调用次数。`writeFile`、`modifyFile`、`deleteFile` 各在补丁执行前原子预扣一个单位；`writeFiles` 按批次计划文件数一次性预扣，预算不足时不扣减任何单位且整批不落盘。补丁后续被变更计划拒绝或执行失败时仍保留已消费预算，防止模型用无效写入绕过上限；成功落盘数量由独立的结构化工作区变更计数记录。
- `GENERATION_<ROUTE>_MAX_REPAIR_ROUNDS`：任务级共享自动修复预算，五个路由默认分别为 `1`、`1`、`1`、`2`、`1`。LIGHT_EDIT 的补丁拒绝重试与验证失败重试、AGENT_EDIT 的验证修复，以及 HEAVY 的生成失败与构建失败修复都消费同一预算；额度不足时不得再创建可选修复模型调用，取消、deadline 和执行上下文错误仍必须上抛。
- `GENERATION_MAX_ROOT_MODEL_ATTEMPTS`、`GENERATION_MAX_MODEL_TURNS`、`GENERATION_MAX_PROVIDER_FAILOVER_ATTEMPTS`：未携带路由 SLA 信封的兼容执行路径预算，默认 `3`、`16`、`4`，上限与路由预算一致；根模型调用预算至少覆盖重型意图路由、首次主生成和已声明修复轮次。
- `GENERATION_<ROUTE>_MAX_MODEL_ATTEMPTS`、`GENERATION_MAX_MODEL_ATTEMPTS`：仅供滚动升级期间兼容旧部署，值只回退到根模型重试预算。新部署必须使用 `MAX_ROOT_MODEL_ATTEMPTS`，不得再用一个变量混合表达根重试、Agent 回合和 provider 故障转移。
- 任务级 provider 粘性仅在一次根模型尝试内生效：fallback 成功后，后续正常模型回合优先复用该 provider，避免反复等待已知故障的首选 provider；当前 provider 失败时仍按候选池环形尝试其余健康 provider。根重试会重新创建任务级模型并重新读取健康模型池与熔断状态；粘性不跨根重试、不跨任务，也不会进入缓存模型或路由模型。
- `AI_MODEL_SECRET_ACTIVE_KEY_ID`：当前 AI 模型凭据 KEK 的稳定版本标识，只允许字母、数字、点、下划线和连字符。轮换时必须使用新 ID，禁止复用旧 ID 表示不同密钥材料。
- `AI_MODEL_SECRET_ACTIVE_KEY`：Base64 编码的 32 字节 AES-256 KEK，由 Secret Manager/KMS 注入；数据库不保存该值。
- `AI_MODEL_SECRET_FINGERPRINT_KEY`：Base64 编码的独立 32 字节 HMAC-SHA256 密钥，必须与 KEK 不同并跨 KEK 轮换保持稳定，使 Benchmark 候选指纹不受随机密文影响。
- `app.ai-model-secrets.previous-keys`：旧 KEK keyring，仅在轮换窗口通过外部 YAML/Secret 配置注入。只要数据库仍存在对应 `secretKeyId`，旧密钥就不得删除；所有节点的 keyring 必须在滚动发布前完成扩容。
- `AI_MODEL_CAPACITY_ENABLED`：集群级模型容量治理开关，开发默认 `false`、生产强制为 `true`；每个真实 provider/model candidate 调用都必须先通过并发、RPM 与 TPM 门禁。
- `AI_MODEL_MAX_CONCURRENT_PER_MODEL`：单个 provider/model 在全后端集群中的最大并发请求数，默认 `4`。
- `AI_MODEL_REQUESTS_PER_MINUTE`、`AI_MODEL_TOKENS_PER_MINUTE`：单个 provider/model 的集群级 RPM、保守 TPM 预算，默认 `120` 和 `500000`。TPM 在请求前按输入估算 token 与受限输出预留量一次性占用。
- `AI_MODEL_MAX_RESERVED_OUTPUT_TOKENS`：单次调用计入 TPM 的最大输出预留量，默认 `16384`，避免模型目录中的极大 `maxTokens` 独占整分钟容量。
- `AI_MODEL_CAPACITY_ACQUIRE_TIMEOUT`：每个容量门禁允许等待的最长时间，默认 `250ms`；超时会在首字节前触发下一个健康候选，而不是在上游无界排队。
- `AI_MODEL_CAPACITY_PERMIT_LEASE`、`AI_MODEL_CAPACITY_HEARTBEAT_INTERVAL`：并发许可采用默认 `60s` 的短 Redis 租约，并由应用级共享调度器每 `20s` 续期；heartbeat 必须不大于租约的一半。正常完成、异常和用户取消会立即释放，节点崩溃后许可最多约一个租约周期即可回收，不再固定占用 20 分钟。
- `AI_MODEL_CAPACITY_MAXIMUM_HOLD`、`AI_MODEL_CAPACITY_MAXIMUM_HOLD_GRACE`：续租的绝对上限默认 `16m`；每次真实 provider 调用使用“该候选 HTTP timeout + `30s` grace”和绝对上限中的较小值。达到上限或丢失租约时，流式调用会取消已暴露的 provider handle 并以可 failover 的容量不可用错误终止，避免心跳把僵死请求永久续租。
- `AI_MODEL_CAPACITY_SCHEDULER_THREADS`、`AI_MODEL_CAPACITY_IDLE_TTL`：所有模型调用共享默认 `2` 个有界 heartbeat 调度线程，续租通过 Redisson 异步命令发起，Redis 网络等待不会串行阻塞整个调度池，也禁止按请求创建线程；空闲 Redis 键默认保留 `2h`，必须大于最大持有时限。
- `AI_MODEL_CAPACITY_FAIL_OPEN`：Redis 容量控制不可用时是否绕过门禁；生产强制为 `false`，禁止因治理依赖故障形成供应商请求风暴和成本失控。
- `AI_CREATE_SPEC_TIMEOUT`：CREATE Spec 模型超时，默认 `10s`，允许范围 `3s` 至 `10s`。
- `AI_PROMPT_CATALOG_ENABLED`：Prompt Catalog 开关，默认 `true`；生产环境必须保持启用，否则应用在启动阶段直接拒绝运行。
- `AI_PROMPT_CATALOG_MANIFEST`：不可变 Prompt 清单位置，默认 `classpath:prompt/prompt-catalog.json`；清单中的每个版本必须绑定实际内容的 SHA-256。
- `AI_PROMPT_ROLLOUT_SALT`：稳定/灰度版本确定性分桶盐值；同一轮灰度期间必须保持稳定，修改会重新分配 cohort。
- `AI_PROMPT_RUNTIME_RELEASES_ENABLED`：数据库运行时发布控制开关，开发默认 `false`、生产默认 `true`；生产环境禁止关闭。
- `AI_PROMPT_RUNTIME_RELEASES_INITIAL_LOAD_REQUIRED`：启动时是否必须成功读取数据库发布头，开发默认 `false`、生产默认 `true`；生产读取失败时应用 fail-fast，不得静默退回随包版本。
- `AI_PROMPT_RUNTIME_RELEASES_REFRESH_INTERVAL`：多节点发布状态刷新周期，默认 `5s`，必须大于 `0` 且不得超过 `5m`。
- `REDIS_CACHE_DEFAULT_TTL`：Redis 业务缓存默认 TTL，默认 `30m`。
- `REDIS_CACHE_GOOD_APP_PAGE_TTL`：优质应用分页缓存 TTL，默认 `5m`。
- `CHAT_MEMORY_KEY_PREFIX`：对话记忆 Redis 键命名空间，默认 `chat-memory:`。
- `CHAT_MEMORY_TTL_SECONDS`：Redis 对话记忆 TTL，默认 `3600` 秒；未配置时兼容读取旧变量 `REDIS_TTL`。
- `CHAT_MEMORY_FALLBACK_MAX_ENTRIES`：Redis 故障期间进程内回退状态总容量，默认 `1000`。容量不足时优先淘汰已同步副本；若容量全部被待回灌更新或删除占用，新变更会明确失败，不会返回成功后静默丢失。
- `CHAT_MEMORY_FALLBACK_EXPIRE_AFTER_ACCESS`：已同步进程内副本的访问过期时间，默认 `2h`；待回灌变更不参与过期淘汰，Redis 恢复后会优先回灌。
- `CHAT_MEMORY_COMPLETED_TOOL_ARGUMENTS_MAX_CHARS`：已成功完成的写文件工具参数在后续模型请求中允许保留的最大字符数，默认 `8192`。超过阈值时仅向模型保留文件路径和内容已落盘占位符，原始执行记忆、失败调用及待审批调用不变，避免整份源码在 Agent 回合间重复传输。
- `WORKING_MEMORY_MAX_TASKS`、`WORKING_MEMORY_RETENTION`、`WORKING_MEMORY_MAX_RECENT_EVENTS`：单实例短期任务工作记忆边界，默认 `2000`、`2h`、`100`；该状态不是跨实例事实源。
- `MILVUS_DATABASE`：Milvus database，默认 `default`；只允许字母或下划线开头的资源名。
- `MILVUS_MEMORY_COLLECTION`：长期记忆 collection，默认 `generation_memory_v2`；生产必须使用显式版本名，不得把不兼容 schema 指向旧 collection。
- `MILVUS_CONNECT_TIMEOUT`、`MILVUS_REQUEST_TIMEOUT`：Milvus 建连和 RPC deadline，默认 `3s`、`5s`。
- `MILVUS_READINESS_TIMEOUT`、`MILVUS_READINESS_REFRESH_INTERVAL`：collection/index 同步创建或 load 的等待上限与运行期重新校验周期，默认 `60s`、`30s`。
- `MILVUS_FALLBACK_MAX_ENTRIES`、`MILVUS_FALLBACK_RETENTION`：单实例有界 fallback 副本容量和保留期，默认 `5000`、`12h`。fallback 不是持久化成功的证据，Milvus 写失败仍必须向 outbox 抛错。
- `MILVUS_MEMORY_TOP_K`、`MILVUS_MEMORY_MINIMUM_SCORE`：长期记忆最大召回数和 COSINE 最低分数，默认 `6`、`0.45`。
- `GENERATION_MEMORY_CONTEXT_PARALLEL_READS_ENABLED`：并行读取最近任务、最近构建日志和长期语义记忆，默认 `false`。三类输入均为只读且结果仍按固定顺序组装；只能在 Benchmark 证明首 Token 延迟下降且质量门禁无回退后灰度开启。
- `GENERATION_MEMORY_CONTEXT_MAX_CONCURRENT_READS`：单实例生成记忆上下文只读 I/O 的全局并发上限，默认 `12`，允许范围 `1` 至 `64`；该上限应与生成任务并发、数据库连接池和 Milvus 查询容量联合评估。
- `GENERATION_MEMORY_CONTEXT_PREPARATION_OVERLAP_ENABLED`：让记忆构建与 Planner、模板准备和项目索引重叠执行，默认 `false`。Context 节点在产出上下文前按任务 deadline 汇合；记忆失败仍阻断生成，编排提前结束会取消未消费的记忆任务。只有 Benchmark 同时证明首 Token p95/p99 改善、质量门禁无回退且依赖池无饱和时才能灰度开启。
- `GENERATION_MEMORY_CONTEXT_MAX_CONCURRENT_PREPARATION_OVERLAPS`：单实例记忆准备重叠任务的全局并发上限，默认 `4`，允许范围 `1` 至 `64`。许可耗尽时调用线程在 deadline 内等待，不会创建无界后台任务。
- `GENERATION_MEMORY_CONTEXT_PREPARATION_OVERLAP_TIMEOUT`：单次重叠任务从提交到 Context 汇合的最大时长，默认 `30s`；实际等待会被任务总 deadline 进一步收紧，超时后中断后台读取并按准备失败处理。
- `GENERATION_MEMORY_CONTEXT_SHUTDOWN_TIMEOUT`：应用关闭时等待生成记忆读取任务收口的上限，默认 `10s`。任务取消会中断尚未完成的读取，不能绕过任务总 deadline。
- `GENERATION_MEMORY_OUTBOX_SCAN_INTERVAL`、`GENERATION_MEMORY_OUTBOX_BATCH_SIZE`：两个 semantic-memory outbox 共用的扫描周期与单轮 claim 上限，默认 `30s`、`50`。
- `GENERATION_MEMORY_OUTBOX_MAX_ATTEMPTS`：generation memory 最大索引尝试次数，默认 `10`；达到上限后保留关系库事实并进入 dead-letter 指标，不得自动删除。
- `GENERATION_MEMORY_OUTBOX_LEASE_DURATION`：多实例 worker 的 claim 租约，默认 `2m`；应覆盖单条 embedding + Milvus RPC 的高分位耗时。
- `GENERATION_MEMORY_OUTBOX_INITIAL_RETRY_DELAY`、`GENERATION_MEMORY_OUTBOX_MAX_RETRY_DELAY`：指数退避起点和上限，默认 `30s`、`1h`。应用删除 outbox 不设最大尝试次数，必须持续重试到成功或人工修复依赖。
- `GENERATION_SESSION_LOCK_STRIPES`：单实例生成启动互斥的固定条带锁数量，默认 `64`，允许范围 `1` 至 `4096`；不同应用允许安全共享条带。
- `GENERATION_SESSION_MAX_TRACKED_SESSIONS`：单实例活动会话与短期回放会话总容量，默认 `1000`，允许范围 `1` 至 `10000`；达到上限后新应用生成请求明确返回服务暂不可用，不会继续扩张内存。
- `GENERATION_SESSION_COMPLETED_REPLAY_RETENTION`：已完成轻量会话的 SSE 回放保留时间，默认 `30s`，必须为正数且不得超过 `1h`。
- `GENERATION_SESSION_CLEANUP_INTERVAL`：过期回放会话批量清理周期，默认 `5s`，必须为正数且不得大于回放保留时间。
- `CODE_OUTPUT_ROOT_DIR`：AI 生成工作区根目录，默认 `tmp/code_output`。
- `CODE_DEPLOY_ROOT_DIR`：已部署应用视图根目录，默认 `tmp/code_deploy`。
- `CODE_SNAPSHOT_ROOT_DIR`：生成事务快照根目录，默认 `tmp/code_snapshot`；必须与生成工作区和部署根目录两两隔离，不得相同或互相包含。
- `GENERATION_TASK_SNAPSHOT_ENABLED`：本机编排诊断快照开关，默认 `true`；关闭后不影响数据库中的生成状态、租约和 trace。
- `GENERATION_REPLAY_SAFE_START_CHECKPOINT_ELISION_ENABLED`：显式可重放 DAG 节点的开始检查点省略开关，默认 `false`。开启前必须用同一数据集比较首 Token 延迟、恢复成功率和交付质量，并采用小流量灰度；未声明可重放契约的节点始终保留开始检查点。
- `GENERATION_REPLAY_SAFE_COMPLETION_CHECKPOINT_COALESCING_ENABLED`：连续可重放节点的完成检查点合并开关，默认 `false`，且只能在开始检查点省略已开启时启用。非可重放节点、合并间隔边界、失败、取消和任务终态仍会持久化；禁止为了减少写入把未验证节点改成可重放。
- `GENERATION_REPLAY_SAFE_COMPLETION_CHECKPOINT_INTERVAL`：连续完成检查点的强制持久化间隔，默认 `4`，允许范围 `2` 至 `64`。默认值最多延后 3 个已完成节点，限制进程丢失后的重放成本和未持久化事件体积。
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
- `DEV_SERVER_VALIDATION_CRITICAL_ERROR_DRAIN_WINDOW`：检测到阻断级错误后继续收集同批诊断的收口窗口，默认 `300ms`，不得超过完整错误收集窗口。
- `DEV_SERVER_OUTPUT_DRAIN_TIMEOUT`：进程退出后等待输出消费任务收口的时间，默认 `2s`。
- `DEV_SERVER_STOP_TIMEOUT`：停止启动中会话时等待补偿清理的上限，默认 `10s`。
- `NODE_TOOLCHAIN_NODE_EXECUTABLE`：所有受控 Node.js 子进程共用的可执行文件名或绝对路径，默认 `node`；Windows 默认解析为 `node.exe`。
- `NODE_TOOLCHAIN_PNPM_EXECUTABLE`：所有受控 pnpm 子进程共用的可执行文件名或绝对路径，默认 `pnpm`；Windows 默认解析为 `pnpm.cmd`。
- `GO_TOOLCHAIN_GO_EXECUTABLE`：受控 Go 构建测试共用的可执行文件名或绝对路径，默认 `go`；容器模式会规范化为镜像内固定的 `go`。
- `DEV_SERVER_PORT_RANGE_START`、`DEV_SERVER_PORT_RANGE_END`：单实例 Dev Server 端口池，默认 `10000` 至 `60000`。
- `DEV_SERVER_MAX_SERVERS_PER_USER`：数据库租约保护下每个用户的全局同时会话上限，默认 `3`。
- `DEV_SERVER_MAX_OUTPUT_LINE_LENGTH`：单行进程输出字符上限，默认 `2000`。
- `DEV_SERVER_MAX_RECENT_OUTPUT_LINES`：单应用内存中保留的最近输出行数，默认 `200`。
- `DEV_SERVER_NODE_ID`：生产节点稳定标识，生产 Profile 必填；滚动发布和进程重启时不得随机生成。
- `DEV_SERVER_LEASE_DURATION`：Dev Server 持久化所有权租约，默认 `30s`。
- `DEV_SERVER_HEARTBEAT_INTERVAL`：持有节点续租间隔，默认 `10s`，必须小于租约时长。
- `DEV_SERVER_RECOVERY_SCAN_INTERVAL`：扫描崩溃节点遗留会话的间隔，默认 `15s`。
- `DEV_SERVER_RECOVERY_BATCH_SIZE`：单轮最多认领并清理的过期会话数，默认 `50`。
- `DEPENDENCY_INSTALL_COMMAND_TIMEOUT`：单次 `pnpm install` 总超时，默认 `5m`。
- `DEPENDENCY_INSTALL_IDLE_TIMEOUT`：安装进程持续无输出超时，默认 `90s`。
- `DEPENDENCY_INSTALL_HEARTBEAT_INTERVAL`：安装过程心跳日志间隔，默认 `15s`，必须小于总超时。
- `DEPENDENCY_RUNTIME_VALIDATION_TIMEOUT`：Vite 运行时加载校验超时，默认 `30s`。
- `PROJECT_COMMAND_GO_TEST_TIMEOUT`：后端 Go 项目执行 `go test -mod=readonly -count=1 -trimpath -buildvcs=false ./...` 的绝对总超时，默认 `3m`，还会受任务 deadline 进一步收紧。
- `PROJECT_COMMAND_GO_TEST_IDLE_TIMEOUT`：Go 构建或测试持续无输出时的超时，默认 `2m`；通常应小于总超时，两类超时或任务取消任一先触发都必须终止整个进程树。
- 全栈工程的 Go 与 Vue 构建在同一质量门禁内并行执行，共享一次 `BUILD_EXECUTION` 预算；普通构建失败会等待另一侧完成以保留完整诊断，执行策略或系统异常会立即中断另一侧。单个全栈任务最多同时运行两个构建组件，在线 Worker 的 CPU、内存和沙箱并发容量必须按该峰值规划。
- `PROJECT_COMMAND_RECENT_BUILD_RESULT_MAX_ENTRIES`：单实例内项目快照最近成功结果和任务级可复用结果各自的 LRU 上限，默认 `500`。Go 结果只允许在同一任务、同一后端绝对路径、完整内容指纹一致且上一次真实测试成功时复用。Vue 除上述条件外，还必须确认 `node_modules`、`dist`、持久化构建状态和当前二次快照全部一致，避免源码先从 A 变为 B、再回到 A 时错误复用 B 的产物。失败、跨任务、缺少任务标识、扫描超限、符号链接、产物不一致或构建期间源码变化均不会授权跳过真实构建；并发相同任务快照由可中断 single-flight 合并，实例重启后必须重新建立任务级成功证据。
- Vue 构建协调通过 `generation_project_build_coordination_total{project_type,event}` 记录真实执行、成功、失败、异常、在途合并、任务复用和守卫拒绝，通过 `generation_project_build_join_wait_duration_seconds{project_type,status}` 记录并发调用等待已有构建的时长。标签只允许固定枚举，不得加入任务 ID、项目路径、应用 ID 或用户 ID。灰度发布必须同时比较 `execution_started` 数量、`task_reused`/`inflight_joined` 命中量、构建失败率、端到端 p95/p99、Runtime/Visual 通过率和自动修复率；只有真实执行次数下降且全部质量指标无回退时才能扩大流量。
- `EXTERNAL_PROCESS_TERMINATION_GRACE_PERIOD`：终止外部进程树时的优雅退出等待时间，默认 `2s`。
- `DEPENDENCY_OUTPUT_DRAIN_TIMEOUT`：进程退出后等待输出消费线程收口的时间，默认 `2s`。
- `DEPENDENCY_INSTALL_MAX_ATTEMPTS`：单项目依赖安装最大尝试次数，默认 `3`，允许范围 `1` 至 `5`。
- `DEPENDENCY_INSTALL_MAX_OUTPUT_LENGTH`：内存中保留的安装输出字符上限，默认 `12000`。
- `DEPENDENCY_INSTALL_LOCK_STRIPES`：本地项目安装锁条带数，默认 `64`；仅用于单实例内并发隔离。
- `GENERATED_CODE_SANDBOX_GO_BUILD_TMPFS_SIZE`：容器内受控离线 Go 测试专用的可执行 tmpfs 上限，默认 `512m`。普通 `/tmp` 必须继续使用 `noexec`，不得为任意生成命令开放可执行临时目录。
- 沙箱镜像必须从仓库根目录构建，并由 CI/发布配置通过 `GENERATED_CODE_SANDBOX_GO_BASE_IMAGE` 提供带真实 `sha256` 摘要的 Go 基础镜像。模板依赖在镜像构建阶段下载、验证和测试，运行时只读使用镜像内模块缓存且禁止联网；Go 模板依赖变化必须触发沙箱镜像重建。
- 后端或沙箱镜像发布前必须执行 `scripts/verify-generated-code-sandbox.ps1` 或 `.sh` 的真实 Docker 契约。详细构建与网络隔离要求见 `docker/generated-code-sandbox/README.md`。
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
- `GENERATION_CONTEXT_MAX_SINGLE_FILE_CHARS`：单个语义索引选中文件可进入生成上下文的最大字符数，默认 `1400`。
- `GENERATION_CONTEXT_MAX_TOTAL_CONTEXT_CHARS`：单次生成项目上下文的总字符预算，默认 `10000`，不得小于单文件字符预算。
- `GENERATION_CONTEXT_MAX_READABLE_FILE_BYTES`：生成上下文允许读取的单个关键文件最大字节数，默认 `1048576`（1 MiB）。
- `AI_CONTEXT_PACK_GENERATION_MAX_TOKENS`：常规生成的结构化上下文包总预算，默认 `2000`，允许范围 `256`～`32000`。
- `AI_CONTEXT_PACK_REPAIR_MAX_TOKENS`：自动修复上下文包的独立总预算，默认 `1500`，允许范围 `256`～`32000`。
- `AI_CONTEXT_PACK_TOKENIZER_MODEL`：用于预算模型输入的 OpenAI-compatible tokenizer，默认 `gpt-4o`。生产集群所有节点必须固定为同一受支持模型，滚动发布期间不得混用不同 tokenizer。
- `AI_CONTEXT_PACK_TOKEN_SAFETY_MARGIN`：兼容供应商 tokenizer 差异和消息 framing 的保守余量，默认 `1.15`，允许范围 `1.0`～`2.0`。
- `AI_CONTEXT_PACK_MAX_SECTION_TOKENS`：单个结构化 section 的上限，默认 `800`，允许范围 `64`～`8000`。
- `AI_CONTEXT_PACK_MINIMUM_SECTION_TOKENS`：可选 section 被保留时的最低预算，默认 `64`，允许范围 `16`～`1000`，且不得大于单 section 上限。
- `AI_CONTEXT_PACK_MAX_SEMANTIC_MEMORY_SECTIONS`：单次调用最多选择的长期语义记忆条数，默认 `6`，允许范围 `1`～`20`。
- `AI_CONTEXT_PACK_SEMANTIC_MEMORY_HALF_LIFE`：语义记忆时间衰减半衰期，默认 `30d`，必须为正数。
- `AI_CONTEXT_PACK_MINIMUM_SEMANTIC_TRUST`：时间衰减后的最低信任权重，默认 `0.25`，允许范围 `0.0`～`1.0`。
- `AI_CONTEXT_PACK_ESTIMATED_CHARS_PER_TOKEN`：已移除，不再读取。生产部署必须改用 `AI_CONTEXT_PACK_TOKENIZER_MODEL` 与 `AI_CONTEXT_PACK_TOKEN_SAFETY_MARGIN`，禁止继续依赖固定字符/token 比例。
- `PROJECT_COMMAND_SNAPSHOT_MAX_FILES`、`PROJECT_COMMAND_SNAPSHOT_MAX_FILE_BYTES`：仅作为旧部署的兼容回退变量保留；新部署应分别改用 `WORKSPACE_MAX_FILES`、`WORKSPACE_MAX_FILE_BYTES`。
- `PROJECT_COMMAND_RECENT_BUILD_RESULT_MAX_ENTRIES`：构建协调内存索引上限，详细的任务隔离、成功限定、Vue 产物守卫和指标契约见上文项目命令配置说明，默认 `500`。
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
- `AI_TOOL_MAX_BATCH_WRITE_FILES`、`AI_TOOL_MAX_BATCH_WRITE_TOTAL_CHARS`：`writeFiles` 单次最多写入的文件数和完整内容总字符数，默认 `20`、`500000`，生产上限分别为 `100`、`5000000`。所有路径先规范化并去重，再通过 ChangePlan、执行 fence 和补丁资源策略；任一文件失败时现有事务补丁会回滚整批。
- `AI_TOOL_LOOP_GUARD_MAX_IDENTICAL_CALLS`：同一任务连续执行完全相同工具调用的上限，默认 `2`；下一次相同调用会在执行前被拒绝，避免重复读写。
- `AI_TOOL_LOOP_GUARD_MAX_NO_PROGRESS_CALLS`：调用签名与结果摘要重复出现的连续无进展上限，默认 `6`；达到上限后下一次工具调用会以不可重试错误终止，避免 A→B→A→B 循环耗尽模型回合。
- `AI_TOOL_LOOP_GUARD_HISTORY_SIZE`：每个任务用于识别循环的脱敏观察窗口，默认 `24`，只保存工具名和 SHA-256 摘要，不保存参数、文件正文或模型输出。
- `AI_TOOL_LOOP_GUARD_MAXIMUM_TRACKED_TASKS`、`AI_TOOL_LOOP_GUARD_RETENTION`：单实例最多跟踪 `10000` 个任务，空闲状态保留 `2h`；人在回路恢复会从 durable transcript 重建窗口。监控 `generation_ai_tool_loop_guard_total{reason,tool}`，并按模型版本观察异常上升。
- `AI_AGENT_PRODUCTIVITY_MAX_READ_ONLY_CALLS_WITHOUT_MUTATION`：同一任务在没有真实工作区写入时允许的累计探索型只读调用数，默认 `8`；达到阈值后下一模型回合会移除读取、搜索和目录工具。尚无成功修改时只保留写入工具并强制调用，避免把模板误判为成品。
- `AI_AGENT_PRODUCTIVITY_MAX_MODEL_TURNS_WITHOUT_MUTATION`：连续无工作区写入的工具回合阈值，默认 `3`；与只读调用阈值任一先达到即触发收敛。
- `AI_AGENT_PRODUCTIVITY_FORCED_ACTION_TURNS_BEFORE_FINALIZE`：已有成功修改后允许的强制执行回合数，默认 `2`；仍无进展时禁用工具并要求模型结束，由构建、测试和运行时校验接管。没有成功修改的任务不会被该策略直接结束。
- `AI_AGENT_PRODUCTIVITY_MAXIMUM_TRACKED_TASKS`、`AI_AGENT_PRODUCTIVITY_RETENTION`：单实例最多跟踪 `10000` 个任务，空闲状态保留 `2h`；审批恢复会从 durable transcript 重建近期只读窗口。监控 `generation_ai_agent_productivity_interventions_total{action,reason}`，并结合模型版本、任务类型和成功率调节阈值。
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
- `TEMPLATE_PRE_WARM_ENABLED`：模板依赖启动预热开关；开发和生产默认开启。预热在应用 Ready 后由独立有界线程池异步执行，不阻塞就绪；资源受限环境可显式关闭。
- `TEMPLATE_PRE_WARM_MAX_CONCURRENCY`：模板预热专用线程池并发上限，默认 `2`，允许范围 `1` 至 `8`。

## Prompt 版本与发布治理

1. 生产环境必须同时启用 `app.ai-prompt-catalog.enabled=true`、`app.ai-prompt-catalog.runtime-releases.enabled=true` 和 `initial-load-required=true`。所有受治理的 AI Service Prompt 都应由 Catalog 选择，禁止绕过版本、Hash、发布记录和调用来源记录。
2. Prompt 版本内容视为不可变制品。修改正文时必须新增版本并更新清单中的 SHA-256，不得原地覆盖已有版本；内容与 Hash 不一致时应用启动失败。
3. `app.ai-prompt-catalog.releases.<prompt-key>` 是随包基线；数据库 `ai_prompt_release` 只保存稳定/灰度版本指针，不保存 Prompt 正文。灰度基于调用 cohort 确定性分桶，同一 cohort 在发布状态不变时始终命中同一渠道。
4. 管理员通过 `/api/ai-prompt/releases/publish` 发布、通过 `/api/ai-prompt/releases/rollback` 回滚，两个接口都必须提交目标 Prompt 当前 `expectedRevision` 和非空变更说明。过期 revision 会被乐观锁拒绝，所有成功操作追加到不可修改的 `ai_prompt_release_history`。
5. 回滚到当前制品已包含的历史版本不需要重新部署；新增 Prompt 正文仍必须作为新版本随制品发布。禁止删除历史版本，以保证模型调用 Provenance、发布历史和 Benchmark 报告可追溯。
6. 每次发布在数据库事务内锁定单例 bundle head、递增全局 revision、更新当前指针并追加审计历史。运行节点以整包快照原子切换，拒绝旧 revision 覆盖新 revision；其他节点最迟在刷新周期内收敛。
7. 每个模型调用只持久化 Prompt Key、版本、渠道、内容 Hash 和 Release Bundle ID，不持久化 Prompt 正文或用户输入。生成基准发布门禁默认拒绝缺少 Prompt Bundle ID 的报告。
8. 监控至少采集 `ai_prompt_release_active_revision`、`ai_prompt_release_refresh_total`、`ai_prompt_release_refresh_duration_seconds` 和 `ai_prompt_release_mutations_total`。应告警持续刷新失败、节点 revision 长时间不一致以及发布冲突异常升高。

## Benchmark 发布来源证明

1. Maven 构建通过 `git-commit-id-maven-plugin` 把完整 Git 提交和 dirty 标记写入 `git.properties`。生产实例缺少该文件、提交不是完整 40/64 位哈希，或制品来自 dirty 工作区时必须拒绝启动；不得用环境变量手工冒充构建提交。
2. `runtimeConfigFingerprint` 由应用对实际生效的生成语义配置自动规范化并计算 SHA-256，覆盖模型调用与容量策略、Agent SLA、上下文预算、工具和补丁边界、构建与依赖安装、工作区限制及 Sandbox 关键资源。密钥、连接凭证和纯日志开关不进入指纹。
3. Benchmark Worker 与发布控制面必须运行同一提交和同一生成语义配置。证据入库与最终发布都会重新比较 Git 提交、运行配置指纹、Prompt bundle 以及模型池指纹；任一项漂移都必须重新评测，禁止人工覆盖门禁。Browser/Backend 评分器的 `enabled` 是进程角色开关，不进入生成运行配置指纹；评分超时、稳定等待和发布门禁参数仍进入指纹，Worker 与控制面必须保持一致。
4. Prompt 发布的模型来源是当前全部已启用模型配置的确定性集合指纹，包含 provider、modelId、密钥不可逆指纹、采样参数、能力和路由顺序。模型新增、禁用、密钥轮换或参数变化后，旧 Prompt Benchmark 证据自动失效。
5. `codegen-vue-project`、`codegen-backend-project`、`codegen-full-stack-project` 的 `v2` Prompt 才会明确优先调用 `writeFiles`；制品默认版本仍为 `v1`，并继续保留用于回滚。新制品中的 `v2` 只表示候选能力可用，必须提交匹配当前构建、运行配置和模型池的 Benchmark 证据后再灰度或发布，禁止直接改写既有 `v1` 内容、哈希或默认版本绕过门禁。

## AI 生成基准与运行时评分

1. 生成发布门禁必须在独立的 Benchmark/CI Worker 执行，不应让在线流量节点常驻 Chrome 或运行候选后端。专用 Worker 必须同时设置 `GENERATION_BENCHMARK_BROWSER_GRADING_ENABLED=true` 和 `GENERATION_BENCHMARK_BACKEND_GRADING_ENABLED=true`，并通过 `SCREENSHOT_CHROME_DRIVER_PATH`、可选的 `SCREENSHOT_CHROME_BINARY_PATH` 固定受支持的 Chrome/ChromeDriver 制品；禁止运行时联网下载驱动。
2. 当前不可变数据集位于 `benchmark/generation-benchmark-dataset-v2.json`，包含 32 条任务，覆盖创建、轻量修改、Agent 修改、Vue、Go 后端、全栈、三档难度和多类业务场景。`GENERATION_BENCHMARK_MINIMUM_TASK_COUNT` 默认 `32`，不得通过降低配置绕过完整数据集。
3. 数据集指纹覆盖数据集 ID/版本、任务元数据、能力标签、必需质量维度、源码夹具内容和固定字符串断言。任一字段变化都会使旧证据失效。声明式评分只允许访问工作区内受控源码路径，不接受正则或可执行表达式，并限制文件数、单文件字符数和总读取字符数。
4. 证据摄取和最终发布都会要求报告恰好覆盖当前数据集：任务不得缺失、重复或未知，执行模式必须匹配；服务端会从任务明细重算成功率、构建率、分位耗时、成本和全部质量统计，拒绝与明细不一致的汇总数字。
5. 质量维度的 evaluation rate 只以声明该维度为必需项的任务为分母。数据集 `2.1.0` 的全部 32 条任务都要求 Runtime：18 条 Vue 任务运行真实 Dev Server，7 条后端任务运行真实 Go sidecar，7 条全栈任务同时运行后端 sidecar 与注入其 API 地址的前端。Visual 覆盖全部 Vue 与全栈任务，共 25 条；纯后端任务不声明 Visual。必需维度默认要求 `1.0` 覆盖率。
6. 浏览器与 HTTP 探测只访问规范化的本机 loopback 地址。Host-Local 后端只监听 `127.0.0.1`；容器后端经端口一致性校验后才在容器内改写为 `0.0.0.0`，并由受控预览网关仅向宿主回环地址发布。Chrome 会关闭后台联网能力并使用仅放行 loopback 的 host resolver 规则；所有候选进程仍由 `GeneratedCodeProcessSandbox` 启动和清理，不允许 Benchmark 自行绕过 Sandbox 创建裸进程。
7. Security Grader 对所有任务扫描敏感文件、硬编码凭据、外部脚本/CDN、动态代码执行、危险 HTML 注入、前端敏感环境变量、路径穿越式敏感文件访问、包管理生命周期脚本和非 registry 依赖。稳定 violation code 写入报告，不把源码、密钥或浏览器原始页面内容持久化。
8. 默认发布门禁要求 Structural、Functional、Diff Scope、Security、Runtime、Visual 必需维度的 evaluation rate 均为 `1.0`；关闭 Browser 或 Backend 任一评分器时仍可生成普通 Benchmark 报告，但发布门禁会因证据覆盖率不足而拒绝晋升。
9. 报告 schema `v3` 除首预览延迟外，还必须记录实际执行使用的 Prompt bundle 与模型池指纹。Worker 必须在整轮任务执行前后分别捕获两项身份，任一项漂移都拒绝生成报告；不得在评测结束后用候选预期指纹覆盖实际执行身份。模型启用候选还必须在其模型类型内置于首选位置，并由物理请求监听器证明至少发出过一次真实请求，禁止仅把候选加入指纹却继续评测旧模型。缺失首预览里程碑使用 `null` 表示，不得按 `0ms` 参与平均值或分位数；默认要求观测率为 `1.0`，并分别对 CREATE、LIGHT_EDIT、AGENT_EDIT、HEAVY_EXPERT 的 p90 以及全局 p99 执行发布门禁。评分协议升级为 `generation-benchmark-graders-v6`，旧证据必须重新评测。
10. `GENERATION_BENCHMARK_BROWSER_SETTLE_DELAY` 默认 `2s`，允许 `0s` 至 `30s`；只用于等待异步首屏稳定，不得用无界 sleep 掩盖应用未就绪。页面加载和脚本超时继续由 `SCREENSHOT_PAGE_LOAD_TIMEOUT`、`SCREENSHOT_READY_STATE_TIMEOUT` 控制。
11. 浏览器诊断输出会标记为 `UNTRUSTED_BROWSER_OBSERVATION`，页面文本和 console 内容只能作为不可信证据，不得被模型当作角色、工具调用或权限指令。
12. 监控至少采集 `ai_generation_benchmark_grader_results_total` 与 `ai_generation_benchmark_grader_duration_seconds`，按有限的 `kind`、`dimension`、`status` 标签告警 Grader 执行错误、Runtime/Visual 通过率下降和评分耗时异常。
13. 模型完成窗口策略灰度必须使用同一不可变数据集、Prompt bundle、模型池和运行配置分别比较端到端 p50/p90/p99、首 Token 与首预览分位、根模型尝试数、Agent 模型回合数、provider 物理请求数、构建通过率、Runtime/Visual 通过率、自动修复触发率和修复成功率。只有 p90/p99 长尾得到可重复改善，且构建、Runtime、Visual、安全、功能与 Diff Scope 门禁均不回退时才允许扩大流量；不得用平均耗时下降掩盖尾延迟或交付质量损失。

### 独立 Worker 执行契约

1. 必须从已提交且与控制面完全相同的制品启动，profile 顺序固定为 `prod,benchmark-worker`。`benchmark-worker` 必须排在最后，以便把在线 Redis 事件流、Redis 任务消费者和后台调度切换为隔离角色配置。
2. Worker 启动后只执行一次完整数据集。执行前解析唯一候选，冻结当前已启用模型池，并把当前持久化 Prompt 状态或待发布 Prompt 候选装载为进程内最高修订版本；普通 Prompt 刷新不能覆盖该快照。
3. 模型启用候选必须是当前存在且停用的配置。Worker 会把它置于自身 `chat`、`reasoning` 或 `routing` 类型的首选位置；模型监听器只在完整门禁执行窗口内对真实 provider 请求计数，窗口开启前和结束后的请求不得计入，整轮零调用时禁止签名。
4. Worker 在任务执行前后重新解析候选结果态，并比较候选指纹、模型池指纹和 Prompt bundle。数据库中模型、密钥指纹、路由顺序、Prompt 指针或候选自身发生任何变化时，本轮结果作废。
5. 门禁通过后，Worker 使用签名协议 `v2` 把候选物理请求次数纳入 HMAC envelope，再经过与控制面相同的验签、执行证明、数据集、grader、Git、运行配置、报告重算和来源校验后入库。模型启用候选计数必须大于 `0`，Prompt 候选必须为 `0`；历史 `v1/0` 证据只保留审计可读性，不得授权新发布。未通过门禁的报告不会生成可发布证据。
6. `GENERATION_BENCHMARK_WORKER_OUTPUT_FILE` 使用同目录临时文件加原子替换写入。结果 schema v2 包含状态、候选身份、候选指纹、`candidatePhysicalRequestCount`、`evidenceId`、violation code 和完整报告；CI 不得解析日志代替该文件。
7. 退出码 `0` 表示证据通过且已入库，`2` 表示完整评测被门禁拒绝，`1` 表示配置、运行环境、身份漂移、候选零调用、签名、验签、持久化或结果输出失败。流水线只能放行 `0`。

模型候选启动示例：

```powershell
$env:SPRING_PROFILES_ACTIVE = "prod,benchmark-worker"
$env:GENERATION_BENCHMARK_WORKER_OUTPUT_FILE = "D:\benchmark\model-42.json"
$env:GENERATION_BENCHMARK_WORKER_CANDIDATE_TYPE = "AI_MODEL_ENABLE"
$env:GENERATION_BENCHMARK_WORKER_MODEL_ID = "42"
java -jar .\target\rush-ai-code-mother-0.0.1-SNAPSHOT.jar
```

Prompt 候选把类型改为 `PROMPT_RELEASE`，并设置 `GENERATION_BENCHMARK_WORKER_PROMPT_KEY`、`GENERATION_BENCHMARK_WORKER_STABLE_VERSION`、`GENERATION_BENCHMARK_WORKER_CANARY_VERSION` 和 `GENERATION_BENCHMARK_WORKER_CANARY_PERCENTAGE`。完整生产 Secret、MySQL、Redis 容量控制、Milvus、OTLP、Chrome/ChromeDriver 与容器沙箱配置仍是必需条件，不能因 Worker 是一次性进程而降低安全基线。

Backend 运行时评分使用以下有界配置：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `GENERATION_BENCHMARK_BACKEND_GRADING_ENABLED` | `false` | 后端与全栈真实运行时评分开关；专用发布门禁 Worker 必须开启 |
| `GENERATION_BENCHMARK_BACKEND_STARTUP_TIMEOUT` | `45s` | 候选后端完成启动和健康检查的总等待上限 |
| `GENERATION_BENCHMARK_BACKEND_REQUEST_TIMEOUT` | `3s` | 单次回环 HTTP 健康请求超时 |
| `GENERATION_BENCHMARK_BACKEND_POLL_INTERVAL` | `100ms` | 后端启动健康轮询间隔 |
| `GENERATION_BENCHMARK_BACKEND_PROCESS_TIMEOUT` | `2m` | 单个候选后端或启动就绪 Go 模板测试的进程总超时，必须大于启动超时 |
| `GENERATION_BENCHMARK_BACKEND_HEARTBEAT_INTERVAL` | `5s` | 受管 Go 进程心跳间隔，必须小于进程总超时 |
| `GENERATION_BENCHMARK_BACKEND_OUTPUT_DRAIN_TIMEOUT` | `2s` | 进程退出后排空有界输出消费者的等待上限 |
| `GENERATION_BENCHMARK_BACKEND_SHUTDOWN_TIMEOUT` | `10s` | 评分结束后等待候选后端进程收口的上限 |
| `GENERATION_BENCHMARK_BACKEND_MAX_OUTPUT_LENGTH` | `65536` | 单个候选后端最多保留的进程输出字符数 |
| `GENERATION_BENCHMARK_BACKEND_MAX_RESPONSE_BYTES` | `65536` | 健康响应体最大字节数 |
| `GENERATION_BENCHMARK_BACKEND_PORT_RANGE_START` | `19000` | 节点本地受控端口池起始端口 |
| `GENERATION_BENCHMARK_BACKEND_PORT_RANGE_END` | `19999` | 节点本地受控端口池结束端口 |
| `GENERATION_BENCHMARK_BACKEND_WORKSPACE_ROOT` | JVM 临时目录下的 `ai-code-mother/benchmark-backend-runtime` | 一次性候选副本与启动就绪检查目录；运行账号必须可创建、写入和删除，禁止与正式生成工作区重叠 |

启用 Backend grading 后，Worker 在接收任务前会把内置 Go+SQLite 模板物化到一次性目录，并通过当前 `GeneratedCodeProcessSandbox` 离线执行 `go test -mod=readonly -count=1 -trimpath -buildvcs=false ./...`。运行时固定设置 `GOPROXY=off`、`GOSUMDB=off`、`GOTOOLCHAIN=local` 等确定性变量，并清理宿主 Go 路径、缓存和私有代理配置。该检查同时验证模板资源、完整 Go SDK、只读模块缓存、沙箱执行链路以及临时目录清理；任一项失败都会阻断 Worker 启动。生产 Worker 推荐使用摘要固定且已通过真实 Docker 契约的容器镜像。Host-Local 仅适合开发诊断，必须提供完整 Go SDK 和可用的离线模块缓存；仅能执行 `go version` 不代表环境可用于评分。

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
11. Agent 模型回合中的 `managePackageJson` 只允许通过工作区补丁修改 `package.json`，不得直接启动 `pnpm install`。依赖安装、锁文件同步与构建统一由后置质量门禁在任务 Deadline、取消信号、进程配额和项目锁约束下执行。

## 生成状态所有权与租约

1. 应用表通过 `generatingTaskId` 和 `generationLeaseUntil` 保存当前生成任务所有权。
2. 启动、阶段更新、流式快照、代码类型回滚和终态释放都必须携带同一个 taskId；旧任务不能覆盖新任务状态。
3. 租约时长为 `GENERATION_TASK_TIMEOUT` 加一分钟安全余量。进程异常退出后，租约过期的新任务可以原子接管状态。
4. 应用状态、生成 trace 和积分结算通过业务事务边界提交；任一步失败都会回滚数据库状态。
5. 部署本版本前必须执行 `V20260714_4__app_generation_state_ownership.sql`。迁移会清理旧版本遗留的无所有者生成标记。
6. 数据库租约只解决状态所有权；多实例共享同一生成目录时，仍必须遵守单写者或分布式项目锁要求。
7. 生产任务分发必须使用 Redis Streams。活动 worker 的消息保留在 Pending Entries List 并由独立心跳续期；消费轮询、数据库访问或工作区物化不能阻塞投递续期。任务进入持久化审批等待后可确认当前消息，审批恢复通过数据库检查点重新取得带 fencing epoch 的执行权。
8. 投递心跳必须使用 `XCLAIM ... JUSTID`，只重置 idle time，不增加 delivery counter。普通 `XCLAIM` 会把每次心跳误计为一次重投，长任务在节点故障后可能因虚高计数被提前送入 DLQ，禁止以普通 claim 实现心跳。
9. `GENERATION_TASK_QUEUE_VISIBILITY_TIMEOUT` 必须大于 `GENERATION_TASK_QUEUE_DELIVERY_HEARTBEAT_INTERVAL`，默认分别为 `45s` 和 `10s`；`GENERATION_TASK_QUEUE_MAX_DELIVERY_ATTEMPTS` 默认 `5`，只统计真实的新投递或过期回收。应告警 DLQ 新增、pending idle 接近 visibility timeout，以及数据库 worker lease 与 Redis 投递所有者持续不一致。

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
6. `generation_orchestration_node_start_checkpoints_total` 按节点记录 `persisted`、`elided`、`failed`，`generation_orchestration_node_start_checkpoint_duration_seconds` 记录对应耗时。`generation_orchestration_node_completion_checkpoints_total` 记录 `persisted`、`coalesced`、`failed`，对应 Timer 为 `generation_orchestration_node_completion_checkpoint_duration_seconds`。只有省略或合并量稳定、失败率不升高且恢复演练通过后，才允许扩大灰度比例。
7. 可重放契约是代码级安全边界：节点必须无副作用，或其文件、缓存、端口等副作用具备幂等实现。新增节点默认不允许省略或合并检查点，禁止仅为降低延迟而直接标记为可重放。

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

## AI 上下文与工具审批恢复要求

1. 结构化上下文包必须使用 tokenizer-backed 预算。中文、emoji 和混合语言不得回退为固定字符/token 比例；安全余量同时作用于总包和 section 截断。
2. 仓库文件、长期记忆、近期任务、构建日志和错误诊断均属于不可信证据。模型输入必须保留 `trust`、`source`、SHA-256 digest 与闭合边界；即使发生二次压缩，也不得产生缺失 `END_UNTRUSTED_*` 的半截数据块。
3. 应用名称、历史模型输出和工具结果不得进入 trusted application scope。上下文中的 `[SECTION]`、`[AI_CONTEXT_PACK]` 与边界标记必须中和，避免历史内容伪造系统 section。
4. 每次模型调用的 provenance 会记录通过结构和 body digest 校验的上下文包摘要、schema、appId、targetType 与 section 数，同时保留完整请求哈希；不得把用户提示词、仓库文件或记忆正文复制到 `generation_model_call.rawMetadataJson`。
5. 破坏性工具审批暂停时，durable transcript 按“系统锚点 → 当前 invocation 用户消息 → 最近完整工具轮次 → 未完成工具调用及已完成 sibling 结果”选择窗口。当前默认最多 `12` 条消息、单消息 `96 KiB`、总 transcript `128 KiB`；摘要校验、请求 ID、工具名和参数必须全部匹配后才允许恢复。
6. 未完成工具调用之后只允许出现同一 assistant tool round 的 sibling 结果。孤立结果、重复 request ID、已解决的待审批请求、非结果消息、必需后缀超限或 transcript 摘要不一致都必须 fail-closed，禁止重新执行可能产生副作用的 sibling 工具。
7. 重型工程生成的活动消息窗口允许在当前轮次保留最多 `8` 条消息，但启动新一轮生成时只预载最近 `4` 条历史消息，避免为了 HITL 恢复无限放大常规模型输入。审批恢复使用独立 `12` 条窗口，不重新加载无关历史会话。

## 长期语义记忆与 Milvus 运行要求

1. Java SDK 只连接 `MILVUS_URI` 指向的 Milvus gRPC/HTTPS 入口。`9091` REST 管理端口和 `2379` etcd 端口不得作为应用数据面地址，也不得直接暴露给公网。生产入口必须启用 TLS 与 token 认证；token 通过 Secret 注入，不得放入 URI user-info。
2. 默认 collection 为 `generation_memory_v2`。它使用显式字段、禁用 dynamic field、应用分配 SHA-256 主键、`AUTOINDEX + COSINE` 和 strong consistency；启动及周期检查会核验字段集合、类型、长度、向量维度、主键、autoID、consistency、index type、metric 和 load state。任何不兼容都必须 fail-closed，禁止修改校验逻辑去“兼容”未知旧表。
3. 授权隔离边界固定为 `tenantId + appId`，`userId` 只表示来源。搜索和删除表达式必须同时包含 tenant/application，返回行还会二次核验 scope；禁止新增只按 `userId`、`appId` 或无 filter 的召回路径。
4. 当前 embedding 契约为 `BAAI/bge-small-zh-v1.5` / `langchain4j-q8-1.17.2-beta27`。每行保存 model/version，召回只允许当前向量空间。更换模型、量化版本、维度、row schema 或 collection contract 时，必须同步递增 `SemanticMemoryContract.INDEX_VERSION`；不得让不同向量空间在同一查询中混算。
5. 记忆正文按 UTF-8 字节有界，metadata 仅允许白名单 scalar，总计不超过 4 KiB，并固定写入 schema、trust 与正文 digest。Milvus 返回的单条坏数据只被隔离并计数，不能让整次召回失败；历史记忆始终作为 `untrusted_history` 进入模型上下文。
6. 进程内 fallback 只用于有界读取降级和临时副本，不是事实源。Milvus upsert/delete 失败必须继续抛错，使 durable outbox 保持 pending；只有 Milvus 与 outbox 同时启用时 generation outbox worker 才会启动，禁止把仅写入本机缓存的记录标记为已持久化。
7. generation memory outbox 使用 attempt CAS、lease owner、lease expiry 和指数退避；达到 `GENERATION_MEMORY_OUTBOX_MAX_ATTEMPTS` 后保留 dead-letter 等待运维修复。应用删除 outbox 在应用删除关系事务内 enqueue，之后按 tenant/application 幂等删除 Milvus，采用无限重试，不得因 Milvus 短暂故障回滚或阻塞用户删除事务。
8. 发布前必须依次执行 `V20260721_1__semantic_memory_production_contract.sql` 与 `V20260721_2__semantic_memory_v2_reindex_contract.sql`。第二个迁移增加 `memoryIndexContractVersion`；旧 v1 记录即使已有 `memoryIndexedAt`，也会被新 worker 重新 claim，并在 contract 切换时获得新的尝试预算，不需要对大表执行一次性清空时间戳。
9. v1→v2 发布时先保留 v1 collection 作为短期回滚资产，再启动 v2 verifier 与重放 worker。只有 `semantic_memory_outbox_pending{outbox="generation"}` 回落、dead-letter 为零、抽样 tenant/app 召回正确并完成备份后才能删除 v1；禁止直接复制缺少 tenant、embedding identity 或 schema digest 的旧行到 v2。
10. `/api/actuator/health` 提供 `milvusMemory` 组件，但默认 readiness group 不包含 Milvus：运行期短故障由 fallback 与 outbox 吸收，不应制造实例级抖动；生产启动仍由 `MILVUS_VERIFY_ON_STARTUP=true` 严格阻断不兼容部署。

首 Token 前的记忆读取通过 `generation_memory_context_reads_total` 和 `generation_memory_context_read_duration_seconds` 观测，标签固定为 `source`、`mode`、`status`。准备重叠通过 `generation_memory_context_preparation_overlap_total` 和 `generation_memory_context_preparation_overlap_duration_seconds` 观测，标签固定为 `phase`、`status`；`phase=execution` 表示后台构建时长，`phase=join` 表示 Context 实际等待时长，`phase=admission` 表示并发许可等待。开启并行读取或准备重叠前后必须比较各来源 p95/p99、失败率、Context 汇合等待、数据库连接池等待和 Milvus 查询饱和度，不能只比较平均耗时。

Prometheus 至少配置以下告警，阈值应按业务 SLO 调整：

```promql
# generation memory 有不可自动重试的记录
semantic_memory_outbox_dead_letter{outbox="generation"} > 0

# 任一 outbox 最老记录超过 15 分钟
semantic_memory_outbox_oldest_age_seconds > 900

# 多实例 lease fencing 持续丢失，通常意味着处理时间超过租约或并发调度异常
increase(semantic_memory_outbox_items_total{outcome="lease_lost"}[10m]) > 0

# Milvus 操作错误率超过 5%
sum(rate(semantic_memory_store_operations_total{status="error"}[5m]))
/
clamp_min(sum(rate(semantic_memory_store_operations_total[5m])), 0.001) > 0.05

# schema/index/load 周期检查失败
increase(semantic_memory_readiness_checks_total{status="failure"}[5m]) > 0
```

同时监控 `semantic_memory_malformed_rows_total`、`semantic_memory_failovers_total`、
`semantic_memory_outbox_backlog_refresh_total{status="error"}` 和 store/readiness/outbox timer 的
p95/p99。重放 dead-letter 前必须先修复根因；不得直接更新 `memoryIndexedAt`、
`memoryIndexContractVersion` 或 deletion outbox `completedAt` 伪造成功。

## 分布式追踪运行要求

1. 生产启动门禁要求 `management.tracing.enabled=true`、OTLP 导出开启且 traces endpoint 非空；Collector 不可用不应阻塞业务线程，但必须通过导出失败指标和日志告警。
2. 应用固定使用 W3C `traceparent` / `tracestate` 传播。生成任务会把受限 carrier 持久化到 durable command，Redis/MySQL Worker、工具审批恢复和跨线程模型回调会继续原 trace。
3. durable payload 不保存 W3C baggage，禁止把用户提示词、密钥、Cookie、租户名称或其他 PII 放入 trace tag。当前 tag 只允许任务、应用、用户数值标识、路由、阶段、状态等诊断元数据。
4. `OTEL_TRACING_SAMPLING_PROBABILITY` 默认 `0.1`，允许按环境调整；故障排查临时提高采样率时必须评估 Collector、存储成本和高基数标签容量，不得长期无界全采样。
5. `OTEL_EXPORTER_OTLP_CONNECT_TIMEOUT` 默认 `3s`，`OTEL_EXPORTER_OTLP_TIMEOUT` 默认 `10s`。应用应只连接同集群或同 VPC Collector，不应让每个实例直接向公网后端导出。
6. 线上至少应能查询以下父子链路：入口 HTTP span → `generation.task.execute` → 模型、工具、构建、验证和 `generated_code.process.*` 子 span。任务暂停审批后恢复时必须仍属于原 trace。

最小 OpenTelemetry Collector 示例：

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318

processors:
  memory_limiter:
    check_interval: 1s
    limit_mib: 512
  batch: {}

exporters:
  otlp/upstream:
    endpoint: tempo:4317
    tls:
      insecure: true

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [otlp/upstream]
```

示例中的上游地址和 TLS 设置必须按实际可观测平台替换；生产环境优先启用 mTLS 或私有网络访问控制。

## 安全约束

1. 生产 Session Cookie 强制启用 `Secure` 和 `HttpOnly`。
2. API 文档和 Knife4j 在生产 Profile 中默认关闭。
3. Actuator health 不返回组件内部详情；外部只暴露 `health`、`info`、`prometheus`。
   - `/api/actuator/health/liveness` 只判断应用进程是否存活，不依赖 MySQL、Redis；失败时应重启实例。
   - `/api/actuator/health/readiness` 同时检查应用就绪状态、MySQL 和 Redis；失败时应停止接收流量，但不应仅因此反复重启实例。
   - `/api/health` 仅为历史兼容的进程存活接口，不能用于判断外部依赖是否可用。
4. CORS 使用显式 Origin 白名单，并在启动期拒绝通配符或格式错误的 Origin。
5. 生产 Profile 不允许依赖 localhost、`root`、弱密码、空 Redis 密码或 localhost CORS 等开发默认值；部署系统未显式注入安全配置时应用必须 fail-fast。
6. AI 模型地址和模型名称在数据库模型目录中维护；数据库只保存 `enc:v1` envelope reference、稳定 HMAC 指纹和 KEK ID，不保存明文 API Key。明文只允许在 provider client builder 边界短暂解析，管理响应、缓存、日志和候选指纹均不得读取或返回明文。
7. 部署 `V20260720_2__ai_model_secret_envelope.sql` 后，启动迁移器会在 Readiness 发布前以 compare-and-set 方式加密历史明文，并清除软删除记录遗留的凭据。缺失 KEK、未知 key ID、损坏元数据或并发迁移出现非安全结果时必须阻断启动，不得回退为明文运行。
   迁移窗口禁止开启 `AiModelMapper` SQL/结果集调试日志；生产 Profile 已将该 mapper 的日志级别固定为 `OFF`，避免历史明文从 SQL 参数或查询结果进入日志系统。
   迁移前备份必须加密、限权并设置最短保留期；迁移验证通过后应轮换现有 provider API Key，因为旧备份、binlog 或历史日志可能仍包含迁移前明文。
8. 建议在网关层进一步限制 Actuator、管理接口和预览资源的访问来源。
9. 限流模块默认不信任 `X-Forwarded-For` 和 `X-Real-IP`。只有直接上游和代理链命中
   `APP_RATE_LIMITER_TRUSTED_PROXIES` 时才会解析转发头；代理必须覆盖客户端传入的同名请求头。
10. 生产默认 `SERVER_FORWARD_HEADERS_STRATEGY=none`，避免 Web 容器在可信代理校验前改写远端地址。
   如基础设施确需启用 `framework`，入口代理必须清洗转发头，并与限流可信代理列表保持一致。

## 本地私有覆盖

本地个性化配置使用 `src/main/resources/application-local.yml`，该文件已被 Git 忽略。启用方式：

```powershell
$env:SPRING_PROFILES_ACTIVE='dev,local'
.\mvnw.cmd spring-boot:run
```

不要修改并提交生产配置文件来保存个人账号或密钥。

## 多节点 Preview 与 HMR 运行要求

1. `DEV_SERVER_NODE_ID` 必须是稳定且可路由的节点标识，只允许字母、数字、点、下划线和连字符；滚动发布时同一节点不得随机变化。
2. `DEV_SERVER_INTERNAL_BASE_URL_TEMPLATE` 生产必填，例如 `http://{nodeId}:8123/api`。该地址必须走集群内网或服务发现，不得经公网入口回源。
3. `DEV_SERVER_INTERNAL_SHARED_SECRET` 生产必填且至少 32 个字符，用于节点间 HTTP/WebSocket Preview 请求的 HMAC。应由 Secret 管理系统注入并定期轮换，不得写入仓库或日志。
4. `DEV_SERVER_INTERNAL_ALLOWED_CLOCK_SKEW` 默认 `30s`；所有应用节点必须使用可靠时钟同步。签名同时绑定方法、路径、查询参数、请求体摘要、时间戳和一次性 nonce。
5. `DEV_SERVER_INTERNAL_REPLAY_CACHE_MAX_ENTRIES` 默认 `10000`，用于有界拒绝重放请求；容量应结合节点间 Preview 握手和资源请求峰值设置。
6. `DEV_SERVER_WS_CONNECT_TIMEOUT` 默认 `5s`，`DEV_SERVER_WS_SEND_TIME_LIMIT` 默认 `10s`，`DEV_SERVER_WS_SEND_BUFFER_SIZE` 默认 `2MB`，`DEV_SERVER_WS_MAX_MESSAGE_SIZE` 默认 `1MB`。这些限制用于阻止慢连接和无界 HMR 缓冲。
7. 浏览器始终访问同源 `/api/app/dev-server/proxy/{appId}/`。入口代理必须为该路径转发 `Upgrade` / `Connection`，关闭响应缓冲，并配置足够长的 WebSocket 读写超时；仓库 Nginx 示例已包含对应 location。
8. 公网入口必须拒绝 `/api/internal/**`。节点间 `/api/internal/dev-server/proxy/**` 仅允许应用节点安全组或服务网格访问；HMAC 不能替代网络隔离。
9. Vite 由受控 Node.js 脚本启动，保留项目原有插件和配置，同时强制应用级 public base，并绕过与该 base 冲突的项目 `/api` 开发代理。HTTP 资源和 `vite-hmr` / `vite-ping` WebSocket 必须使用同一应用级路径。
10. 浏览器 Cookie、Authorization、转发头和伪造的 `X-AI-Preview-*` 头不得传入生成应用。远程节点签名头只能由入口节点重新生成，owner 节点还必须二次校验数据库租约、nodeId、leaseOwner、本地进程和端口。
