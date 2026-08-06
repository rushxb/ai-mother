# 阶段一实施计划：基线冻结与上下文底座

依据 `docs/AI生成项目或改造项目链路优化清单.md` §24 阶段一。三项工作，分三个可独立回退的提交单元。

## 前置纠正：清单 §16.2 状态失真

清单 §16.2「实施状态（2026-07-29）」声称已交付以下内容，但工作树与 `git log --all` 中均不存在，该轮改动从未落库：

- `sql/migrations/V20260729_1__generation_episodic_outcome_quality.sql`
- `GenerationTask` 的 8 个 outcome 字段
- `service/trace/GenerationOutcomeQuality`
- `completeTask(.., outcomeQuality)` / `completeGeneration*(.., outcomeQuality)` 重载
- `GenerationOutcomeQualityTest`

§24 阶段一把「L3 情景记录最小字段」标为「待开始」是正确的。本轮把 §16.2 的「已完成」改为准确表述，并在实施后回填真实结果。

---

## 单元一：生成策略资源边界收口（工作树已有半成品，补齐门禁）

现状：6 个 Properties 类已移除 `@ConfigurationProperties`、常量化并进入发布指纹；`application.yml` / README / 生产配置文档已同步；`HardcodedGenerationPolicyWiringTest` 已验证外部属性不能覆盖。定向测试通过。

缺口：只有 wiring 层门禁，缺架构层门禁 —— 后续有人重新加回 `@ConfigurationProperties` 或在 yml 里恢复这些前缀时，现有测试不会失败。

### 改动

1. 新增 `src/test/java/com/rush/rushaicodemother/architecture/HardcodedGenerationPolicyArchitectureTest.java`：
   - 6 个 Properties 类源码不得包含 `@ConfigurationProperties`；
   - `application.yml` / `application-prod.yml` 不得包含 `app.generation-project-context.`、`app.edit-locator.`、`app.patch-execution.`、`app.ai-tool-workspace.`、`app.ai-tool-loop-guard.`、`app.ai-agent-productivity.` 这 6 个前缀；
   - `GenerationRuntimeConfigurationFingerprintService.INCLUDED_PREFIXES` 不得包含上述前缀，且 `HARDCODED_POLICY_VALUES` 必须覆盖全部 6 项；
   - 每个类的常量必须与同名实例字段默认值一致（反射比对，防止常量与默认值漂移）。
2. `GenerationRuntimeConfigurationFingerprintServiceTest` 补一项：`equivalentPropertyInsertionOrderMustRemainDeterministic` 目前用 `app.patch-execution.max-operations` 作为「会进入指纹的属性」，该前缀已被排除，测试现在只靠 `ai-context-pack` 一项生效 —— 换成仍在 `INCLUDED_PREFIXES` 内的属性，恢复测试原意。

---

## 单元二：Latency Ledger 分路由准备阶段占比

目标（清单 §24 阶段一）：按路由给出 `preparation/context/model/verification/publish` 分段 p50/p90/p99，供阶段三的并行决策使用；样本不足时必须显式暴露，不得让调用方误判。

### 现状

- `GenerationTaskLatencyLedgerService` 只能按单个 `taskId` 做非重复计数归因，无路由级聚合。
- `GenerationDurationProfileService` 有路由级 p50/p90，但维度是 `(stage, category)` 原始笛卡尔积（`maxStageProfiles=100`），且只有 p50/p90、无 p99、无样本完整率，无法直接回答「准备阶段占 TTP 多少」。
- span 的 `category` 是 10 值枚举 `GenerationSpanCategory{QUEUE,WORKSPACE,PIPELINE,MODEL,TOOL,DEPENDENCY,BUILD,VALIDATION,REPAIR,FINALIZATION}`，stage 是 ~40 个自由字符串。
- `GenerationTaskSpanMapper.selectRecentSuccessfulByRoute` 已提供按路由的有界 span 查询（`INNER JOIN generation_task`，`LIMIT`）。

### 设计：把 10 个 category 折叠为 5 个决策分段，复用既有查询

不新增表、不新增 SQL、不新增线程池。新增一个纯计算的深模块，数据源复用 `GenerationDurationSampleRepository`。

分段映射（`GenerationLatencySegment` 枚举）：

| 分段 | 归入的 span category | 语义 |
| --- | --- | --- |
| `PREPARATION` | `QUEUE`, `WORKSPACE` | 排队、工作区物化 —— 阶段三可重叠的部分 |
| `CONTEXT` | `TOOL`, `DEPENDENCY` | 索引扫描、只读工具、依赖元数据 |
| `MODEL` | `MODEL` | 模型推理，不可并行 |
| `VERIFICATION` | `BUILD`, `VALIDATION`, `REPAIR` | 构建、校验、修复 |
| `PUBLISH` | `FINALIZATION` | 提交、发布、预览 |

`PIPELINE` 不映射到任何分段：它是父跨度（`GenerationTaskLatencyLedgerService` 已把它当作 inclusive 容器、归因时排在最后），计入分段会与子跨度双重计数。它单独作为 `taskTotal` 的参照。

### 改动

1. 新增 `monitor/latency/GenerationLatencySegment.java`：枚举 + `fromCategory(String)` 静态映射，未知 category 返回 `Optional.empty()`（不落到任何分段，计入 `unmappedSpanCount`）。
2. 扩展 `GenerationDurationSampleRepository`：新增 `loadRecentSuccessfulSegmentSamples(route, taskSampleLimit, spanSampleLimit)`，返回 `(taskDurationsMs, List<SegmentDurationSample>)`。实现落在既有 `MyBatisGenerationDurationSampleRepository`，复用现成的两条 mapper 查询，不写新 SQL —— 保持 `GenerationDurationProfileArchitectureTest` 的「domain 依赖 port 而非 mapper」边界。
3. 新增 `monitor/latency/GenerationRouteLatencySegmentProfile.java`（record）：
   - `route`、`taskSampleCount`、`spanSampleCount`、`unmappedSpanCount`、`sampleCompletenessPercent`、`calculatedAt`
   - `taskTotalP50/P90/P99Ms`
   - `List<SegmentLatency>`：`segment`、`spanCount`、`p50/p90/p99Ms`、`taskP90SharePercent`（该分段 p90 ÷ 任务总时长 p90，即清单 §19.2.3 的「准备阶段占比」）
   - `boolean sufficientForParallelDecision`：`taskSampleCount >= 100` 且 `sampleCompletenessPercent >= 95`（清单 §24 阶段一门禁值）
4. 新增 `monitor/latency/GenerationRouteLatencySegmentService.java`：分位数计算复用 `GenerationDurationProfileService` 的 `ceil(p*n)-1` 算法（保持全项目分位数语义一致），Caffeine 缓存复用 `GenerationTaskProgressProperties` 的 `maxCachedRoutes` / `profileCacheTtl`，不新增配置项。
5. 新增 `model/vo/GenerationRouteLatencySegmentVO.java` + `GenerationPerformanceController` 加 `GET /generation-performance/admin/routes/{route}/latency-segments`，沿用既有 `@AuthCheck(mustRole = ADMIN_ROLE)` 与 `@Pattern(regexp = ROUTE_PATTERN)`。
6. 测试：
   - `GenerationLatencySegmentTest`：10 个 category 的映射、`PIPELINE` 不映射、未知值不映射。
   - `GenerationRouteLatencySegmentServiceTest`：分位数正确性、`PIPELINE` 不双重计数、空样本返回零值不抛异常、`sufficientForParallelDecision` 在 99 样本 / 100 样本的边界、缓存命中不重复查询、非法 route 拒绝。
   - `GenerationDurationProfileArchitectureTest` 补断言：新 port 方法存在且 `monitor/latency` 不依赖 `.mapper.` / `infrastructure.persistence`（`GenerationSpanPersistenceBoundaryArchitectureTest` 已对整个 `monitor` 目录做此约束，新类会自动纳入，补一条针对新 port 的显式断言）。

不做：不改 span 写入侧、不改 `GenerationSpanCategory` 枚举值、不改现有 `getLedger` 行为。纯新增读路径。

---

## 单元三：L3 情景记录最小字段

按清单 §16.2 修正方案与 §20.1 设计修正：不新建表、不新建 outbox、不引入独立写入，把字段折叠进既有终态 UPDATE。

### 3.1 数据库

`sql/migrations/V20260804_1__generation_episodic_outcome_quality.sql`（8 列 + 1 索引 + CHECK 约束）：

| 列 | 类型 | 语义 |
| --- | --- | --- |
| `thinkingMode` | varchar(16) null | 实际思考档位；依赖 §17.2，本轮恒为 NULL |
| `changedFileCount` | int null | 有效变更文件数 |
| `firstBuildPassed` | tinyint null | 是否免修复通过构建 |
| `repairRounds` | int null | 实际修复轮次 |
| `firstPreviewMillis` | bigint null | 提交 → 可预览（TTP） |
| `failureCategory` | varchar(64) null | 复用 `GenerationErrorClassifier` 分类字面值 |
| `reworkedAt` | datetime(6) null | 交付后返工时间；依赖 §18.4，本轮恒为 NULL |
| `distilledAt` | datetime(6) null | 已蒸馏时间；依赖 §16.3，本轮恒为 NULL |

- 全部可空，NULL 语义为「未采集」。
- 索引 `idx_generation_task_distill_claim (distilledAt, status, isDelete, endTime, id)`，为 §16.3 的单表租约扫描预留，复用 `idx_memory_outbox_claim` 的列序惯例。
- CHECK：`changedFileCount >= 0`、`repairRounds >= 0`、`firstPreviewMillis >= 0`、`firstBuildPassed IN (0,1)`（均写成允许 NULL 的形式）。
- 迁移用 `information_schema` 幂等守卫，与 `V20260716_3` 同风格。
- 同步 `sql/create_table.sql` 基线与 `sql/migrations/README.md`。

### 3.2 值对象与写入通道

1. 新增 `service/trace/GenerationOutcomeQuality.java`（record，8 个可空字段）：
   - 静态工厂 `empty()`（全 null）、`builder` 风格的 `with*`；
   - 归一化：负数 → 拒绝（`IllegalArgumentException`，中文消息）；`failureCategory` 截断到 64 且小写；`thinkingMode` 截断到 16。
2. `GenerationTraceMapper.completeRunningTask` 用 `COALESCE(#{field}, field)` 折叠 8 列 —— 传 null 不覆盖已采集值，重试安全。fencing / 乐观锁 / `WHERE status='running'` 条件完全不变。
3. `GenerationTracePersistenceService.completeRunningTask` 与 `GenerationTraceService.completeTask` 各加一个带 `GenerationOutcomeQuality` 的重载。
4. `GenerationTaskLifecycleService.completeGeneration` / `completeGenerationAndCharge` 各加带 `outcomeQuality` 的重载。

**关键约束（清单 §16.2 已记录过的踩坑）**：旧签名必须保持直接调用原协作路径，不得改成委托到新重载。上一轮改成委托链导致 `GenerationTaskLifecycleServiceTest` 的协作顺序断言失败，那是真实回归。新重载与旧签名各自独立调用 `generationTraceService`。

### 3.3 指标采集接入（5 项无阻塞指标）

统一在两条终态汇聚点装配，不散落到各 pipeline 内部：

**A. `GenerationPipelineExecutor.completeManagedTask` / `failManagedTask`**

- `changedFileCount`：`GenerationPipelineOutcome` 新增 `changedFileCount`（`Integer`，可空）与 `repairRounds`（`Integer`，可空）两个字段。`AgentEditGenerationPipeline` 已有 `editResult.changedFiles().size()` 与 `editResult.repairRounds()`；`SlotFillGenerationPipeline` / `LightweightEditGenerationPipeline` 填各自的 mutation count。沿用 `GenerationPipelineOutcome` 既有的兼容构造器模式，旧构造器补 null，不破坏现有测试。
- `failureCategory`：`failManagedTask` 已持有 `Throwable failure`，调 `GenerationErrorClassifier.classify(failure).category()`。
- `firstPreviewMillis`：`execution.session().executionContext().firstPreviewReadyAt()` 已存在（`AtomicReference<Instant>`，`markFirstPreviewReady` 设置且支持恢复），减 `execution.submittedAt()`；未就绪则 null。
- `firstBuildPassed`：`repairRounds == 0 && 构建证据存在` → true。构建证据取 `outcome.completionEvidence().contains(BUILD_VALIDATION)`；`repairRounds > 0` → false；两者都无信息 → null。

**B. `HeavyGenerationSessionCompletionService.completeClaimed`**

- `changedFileCount`：`preparation.artifact("diff_summary")` 的 payload 已含 `addedCount/modifiedCount/deletedCount`（见 `DiffSummary`），三者求和。
- `repairRounds`：Heavy 路径修复轮次目前只在 `BuildFixAgentNode` payload（`repairRounds`）里，从 `preparation.artifact` 取；取不到则 null。
- `failureCategory`：`GenerationTerminalOutcome` 非 SUCCESS 时映射到分类字面值（新增私有映射，取值限定在 `GenerationErrorClassifier` 的常量集合内）。
- `firstPreviewMillis` / `firstBuildPassed`：同 A，走 `session.executionContext()`。

`thinkingMode`、`reworkedAt`、`distilledAt` 本轮恒传 null，在清单里明确标注其阻塞依赖（§17.2 / §18.4 / §16.3）。

### 3.4 测试

- `GenerationOutcomeQualityTest`：8 字段边界、负值拒绝、截断、`empty()` 全 null。
- `DefaultGenerationTraceServiceTest`：新重载把 outcomeQuality 透传到 persistence；旧签名协作路径不变（防止再次退化为委托链）。
- `DefaultGenerationTracePersistenceServiceTest`：null 与非 null 两种 outcomeQuality 的 mapper 参数断言；终态非法 / 时间非法仍拒绝。
- `GenerationTaskLifecycleServiceTest`：新重载的协作顺序（release → completeTask → charge），旧签名断言保持不动。
- `GenerationPipelineExecutorTest`：成功路径带 changedFileCount/repairRounds/firstPreviewMillis；失败路径带 failureCategory；首预览未就绪时 firstPreviewMillis 为 null。
- 新增 `architecture/GenerationEpisodicOutcomeQualityArchitectureTest`：
  - 迁移与基线都含 8 列、索引、4 个 CHECK；
  - mapper 的终态 UPDATE 必须用 `COALESCE`（防止后续有人改成直接赋值而擦掉已采集值）；
  - `generation_task` 不得出现第二处写这 8 列的 SQL（单一写入所有权）；
  - 不新增 outbox 表 / 不新增 `generation_episode` 表。
- `FlywayMigrationArchitectureTest` 的版本唯一性检查自动覆盖新迁移。

---

## 验证与文档

1. `bash ./mvnw -o test`：0 failure / 0 error（当前基线 2494 通过 / 40 跳过，本轮新增测试后基数上升）。
2. `bash ./mvnw -o -Pgeneration-release-smoke test`：8 项全通过。
3. 前端本轮无契约变更（新增接口是 admin 诊断只读，前端未消费），不跑前端构建；如实说明未跑及原因。
4. 文档同步：
   - `docs/AI生成项目或改造项目链路优化清单.md`：修正 §16.2 状态失真；§24 阶段一三项状态更新为「已完成」并附真实回归数据；§16.2「未完成：指标采集」表更新为已接入 5 项、剩 `thinkingMode` 待 §17.2。
   - `README.md` / `docs/backend-production-configuration.md`：新增 admin 接口说明；本轮无新增环境变量（复用 `app.generation-progress.*`），明确记录这一点。
   - `sql/migrations/README.md`：新增迁移条目。

## 不做（避免技术债）

- 不新建 `generation_episode` 表、不新建 outbox、不新建挂起状态机（清单 §22）。
- 不新建线程池，不改 span 写入侧。
- 不实现 §16.3 蒸馏、§16.4 分型召回、§16.5 语义缓存、§16.6 会话压缩 —— 那些属阶段二/三。
- 不动 `HeavyGenerationCoordinator` 的职责边界（清单 §23.5），新逻辑落在 `HeavyGenerationSessionCompletionService` 与 `GenerationPipelineExecutor` 既有终态汇聚点。
- 不留 TODO；`thinkingMode`/`reworkedAt`/`distilledAt` 的「恒为 null」是明确记录了阻塞依赖的设计状态，不是未完成的捷径。
