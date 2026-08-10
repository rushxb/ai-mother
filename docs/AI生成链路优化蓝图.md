# AI 生成链路优化蓝图

审计范围：`orchestration/**`、`ai/**`、`core/**`、`infrastructure/**` 全量生成链路（1204 个 Java 文件，约 13.6 万行）。
审计基线：`ce4bcc7`（dev 分支）。`bash ./mvnw -o -DskipTests compile` 通过。

---

## 0. 一句话结论

**工程底座已经领先，Agent 内核仍是山寨水位。**

预算协议、租约栅栏、完成证据门禁、幂等唯一键、路由策略链这些「难做对」的部分做得比多数同类项目扎实；
但真正决定用户感知的三件事 —— **Agent 工具循环、意图理解、首屏时延** —— 还停在教程级实现。

优化重心因此不是「继续加工程设施」，而是**把已有工程能力兑换成生成质量与速度**。

---

## 1. 客观评价：哪些部分已经不需要动

先明确不要浪费改造预算的地方（这些是本项目的真实资产）：

| 模块 | 判断 | 依据 |
| --- | --- | --- |
| 成本/预算控制 | **优秀** | `GenerationBudgetKind` 六类预算 + `GenerationRuntimeProperties:28,30`（3 根尝试 × 4 failover 硬上限）。retry/failover/hedge 共享同一份被计量的预算，不存在指数放大 |
| 积分预授权 | **优秀** | 提交即 `quote()` 预授权（`GenerationTaskAdmissionService:72`），终态按真实 token 结算（`UserCreditServiceImpl:226-236`），余额 `bigint` + 非负 CHECK |
| 租约与执行纪元 | **优秀** | `GenerationExecutionFence` + `finishIfOwned`（`GenerationPipelineExecutor:502`），旧 worker 无法拆掉新纪元上下文 |
| 完成证据门禁 | **优秀** | `GenerationCompletionPolicy` 要求意图覆盖 + 工作区结果 + 分级验证证据，且终态前重读持久租约 |
| 路由/管线扩展性 | **良好** | `GenerationRoutingPolicy` + `GenerationPipeline` 双 `@Order` 策略链，新增场景是加类而非改 switch，符合开闭原则 |
| 数据库 schema | **良好** | FK、租户复合索引、幂等唯一键（`uk_generation_task_submission_idempotency`）、CHECK 约束齐备 |
| 配置下沉 | **良好** | yml 仅 213 行且只留环境相关项，内部策略进常量并纳入发布指纹 |

**不建议**在这些方向再投入重构，边际收益已经很低。

---

## 2. 核心优化项（5 项，按「见效速度 × 影响面」排序）

### P0-1｜Agent 工具调用串行执行 —— 单点最大提速

**问题**（CONFIRMED）

同一批 tool_calls 严格串行执行：

`DefaultGenerationAgentRuntime.java:560`
```java
for (int index = 0; index < aiMessage.toolExecutionRequests().size(); index++) {
    ToolExecutionRequest toolRequest = aiMessage.toolExecutionRequests().get(index);
    ToolExecutionResult result = executeToolWithSpan(...);   // 阻塞等待
```

模型一次返回 5 个 `readFile` 时，耗时是 5 次串行 IO 之和。Claude Code / Codex 对只读工具一律并发。
`ReadMultipleFilesTool` 只是缓解手段 —— 它要求模型「先想清楚读哪些」，而模型并不总会这么做。

**改法**

按工具风险分级并发，语义不变：
- 只读工具（`FileReadTool`、`FileDirReadTool`、`ReadMultipleFilesTool`、`ProjectSearchTool`、`DiffSummaryTool`、`ProjectHealthCheckTool`）→ 虚拟线程并发执行；
- 写/构建/审批类工具（`FileWriteTool`、`FileModifyTool`、`FileBatchWriteTool`、`FileDeleteTool`、`PackageManagerTool`、`VueProjectBuildTool`、`SnapshotRollbackTool`）→ 保持串行；
- 结果**按原请求顺序**写回 `memory`，保证对话历史与重放确定性；
- 复用 `ToolRiskLevel`，不新增枚举。

**预期**：Heavy/AgentEdit 路径的工具阶段耗时下降 40%~60%（取决于模型批量调用比例）。

**验收**：新增测试断言「3 个只读工具并发执行总耗时 < 串行之和」，且 `memory` 中 `ToolExecutionResultMessage` 顺序与请求顺序一致。

---

### P0-2｜首屏预览只在全部验证通过后才发布 —— TTP ≈ 总时长

**问题**（CONFIRMED）

`markFirstPreviewReady()` 全项目只有一个调用点，位于最终发布之后：

`GenerationWorkspaceReleaseService.java:46-48`
```java
GenerationWorkspacePublicationResult result =
        publicationService.publishWithMetadata(session, publicationMetadataService);
publishFirstPreviewSafely(session, targetType);   // 唯一的首预览发布点
```

而 `releaseVerified` 由 `GenerationPipelineExecutor:260` 在 `requireCompletable`（含 BUILD/EXPERT 验证）**之后**调用。
结果：用户「看到第一个可用页面」的时间等于「整个任务完成」的时间，`firstPreviewMillis` 这个 SLA 指标目前恒等于任务总时长，失去诊断意义。

这是本项目**用户体验的头号问题**：Lovable / v0 类产品的核心体验就是「几秒内看到东西，然后逐步变好」。

**改法**

区分两级里程碑，两者都已有事件类型（`FIRST_PREVIEW_READY`）：
1. **可预览（provisional）**：工作区首次具备可渲染入口（模板物化完成 + 首批文件落盘）即发布，走 dev-server 预览路由，事件带 `previewLevel=provisional`；
2. **已验证（verified）**：保持现状不变，`previewLevel=runtime|build`。

约束：provisional 预览**不得**触发计费、不得写 `generation_task` 终态、不得进入完成证据集合 —— 只是让用户先看到。
`GenerationFirstPreviewMilestone` 增加 `level` 字段，SLA 只对 provisional 计时（这才是真正的 TTP）。

**预期**：用户感知首屏从「分钟级」降到「模板物化完成即可见」，CREATE 路径尤其明显。

---

### P0-3｜意图理解是 18 组硬编码关键词 —— 路由误判的根因

**问题**（CONFIRMED）

`IntentProfileService` 用 18 个 `List<String>` 关键词表推断意图、复杂度、破坏性风险、影响文件数：

`IntentProfileService.java:76-82`
```java
private static final List<String> MULTI_FILE_KEYWORDS = List.of(
        "跨文件", "多个文件", "多文件", "前后端", "全栈", "整个项目", ...
private static final List<String> SINGLE_FILE_KEYWORDS = List.of(
        "单文件", "一个文件", "当前文件", "这个文件", "single file", ...
```

`IntentProfile` 是路由、验证级别、预算、计费报价的**共同上游**。关键词命不中就全链路降级：
「把登录页做得好看点」不含任何 `LIGHT_EDIT_KEYWORDS`，也不含 `HIGH_COMPLEXITY_KEYWORDS` —— 落到默认分支。
真实用户不会按关键词表说话，这是「该轻不轻、该重不重」的结构性来源。

**改法**（分两步，第一步即见效）

1. **保留关键词作为高置信快路径**，但只在明确命中时短路（当前已是此结构，无需改动）；
2. **低置信时补一次小模型结构化分类**：复用已有的 `DeterministicCodeGenTypeRouter` + `CreateSpecService` 的小模型调用模式，输出 `IntentProfile` 的结构化字段（operationType / complexity / scopes / expectedFileCount）。
   - 超时 ≤ 2s（复用 `CREATE_SPEC_TIMEOUT` 同档），失败即回落关键词结果，**不阻塞主链路**；
   - 消耗 `ROOT_MODEL_ATTEMPT` 预算，纳入现有计量，不产生不受控成本；
   - 结果进 `GenerationRoutingSignal`，下游零改动。

已有 `GenerationShadowRoutingService` 影子路由设施，可先以 challenger 身份灰度对比准确率，再切主路由 —— 这是本项目现成的正确做法，直接用。

**预期**：路由准确率提升，避免简单需求走 Heavy（省时省钱）与复杂需求走 LightEdit（返工）两类误判。

> **落地修正**：上面第 1、2 条与「影子灰度先行」的前提经代码核实**均不成立**（置信度是加性常量分，
> 无法做门禁；且没有任何路由策略读 `IntentProfile`）。实际改法与作用域见 §5.4。

---

### P1-4｜上下文「压缩」实为字符截断 + 12 条消息窗口 —— 生成质量天花板

**问题**（CONFIRMED，两处叠加）

其一，所谓压缩就是掐头留尾：

`GenerationContextCompressionServiceImpl.java:44-50`
```java
int headBudget = Math.max(maxChars * 2 / 3, 1);
String head = normalized.substring(0, Math.min(headBudget, normalized.length())).trim();
// ... "[上下文已自动压缩，省略中间 N 个字符]"
```

按字符数在任意位置切断，会把 JSON、代码块、`import` 语句截成语法碎片再喂给模型。

其二，Agent 对话窗口只有 12 条消息：

`DefaultGenerationAgentRuntime.java:75`
```java
private static final int HEAVY_CHAT_MEMORY_MESSAGES = 12;
```

一轮工具调用产生 assistant + N 个 tool_result，12 条约等于 4~5 轮工具循环。超出即**静默丢弃最早消息** ——
模型会忘记自己已经改过哪些文件、之前的构建错误是什么，导致重复劳动和反复修同一处。

**改法**

1. 截断改为**结构感知**：按 Markdown / 代码块 / 文件条目边界切分，宁可整块丢弃也不切碎语法单元；被丢弃的块保留一行摘要（路径 + 行数）；
2. 窗口溢出改为**摘要式压缩**而非丢弃：把最早的 K 轮工具循环折叠成一条系统摘要（已读文件列表、已改文件列表、未解决的构建错误），保留最近 N 轮原文。这正是 Claude Code 的 compaction 思路；
3. 消息数窗口改为 **token 预算窗口**（已有 `AiContextTokenEstimator`、`AiContextPackBudgeter` 可直接复用），避免「12 条」在长短消息下含义漂移。

**预期**：Heavy 路径的重复改动和反复修复轮次显著下降，`repairRounds` 指标可直接验证。

---

### P1-5｜生成产物与 API 同源，无 CSP —— 上线前必须处理

**问题**（CONFIRMED）

`/static/{deployKey}/**` 无鉴权、以 `text/html` 提供 AI 生成页面，且与 `/api` 同源：

`StaticResourceController.java:73-78`
```java
return ResponseEntity.ok()
        .contentType(contentType)            // .html → text/html;charset=UTF-8
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")   // 只有 nosniff，没有 CSP
```

会话 Cookie 是 `http-only: true`（`application.yml:31`），能挡住 `document.cookie` 直读，但挡不住**同源带凭据请求**：
生成页面里的一段 `fetch('/api/app/list/my', {credentials:'include'})` 就能以受害者身份调用全部业务接口。
`/app/dev-server/proxy/{appId}/**` 更严重 —— 它经过登录鉴权，同源同凭据，生成代码天然运行在已认证上下文里。

注意：这里的「攻击者」不必是外人。**模型自己被 prompt injection 诱导生成一段外发脚本**，就构成用户数据泄露。

**改法**（按成本排序，任一即可显著降风险）

1. **最低成本**：给两个产物端点加严格响应头 —— `Content-Security-Policy: sandbox allow-scripts; default-src 'self'; connect-src 'none'`、`X-Frame-Options`/`frame-ancestors` 限定到自家控制台域名。`connect-src 'none'` 直接掐断同源 API 调用；
2. **推荐**：产物走独立域名/子域（`*.preview.example.com`），与 API 彻底跨源，Cookie 天然不随行；
3. 前端预览容器统一改用 `<iframe sandbox>`。

**这是唯一一条「不修不能对外服务真实用户」的项目**，建议排在第一位落地（改动量最小，风险最高）。

---

## 3. 确认可清理项（低风险，顺手做）

以下均已逐个追溯可达性，删除不影响主链路：

| 目标 | 依据 | 处置 |
| --- | --- | --- |
| `GenerationTaskOrchestrator.getStream(Long)` | 全仓库无调用方（SSE 实际走 `AppServiceImpl.getGenerationStream` → `GenerationTaskQueryService`） | 删除 |
| `GenerationPipelineExecutor` 两个兼容构造器（`:72`、`:95`） | 向 3 个协作者传 `null`，仅测试使用；正式 Spring 走全参构造 | 删除，测试改用全参构造 + mock |
| `GenerationCompletionPolicy()` 无参构造（`:34`） | 置 `generationTaskFenceGuard = null`，使 `runtimeOwnershipValid` **静默跳过栅栏校验** —— 门禁被绕过而不报错 | 删除；测试注入 mock guard。这条不只是冗余，是安全门禁的后门 |
| `GenerationWorkspaceReleaseService.release()`（`:56`） | `@Deprecated`，纯委托 `releaseVerified` | 删除并改调用点 |
| `GenerationPipelineOutcome` 两个兼容构造器（`:64`、`:74`） | 全部委托到全参构造 | 保留（record 兼容成本低），但新代码禁用 |

同类「兼容构造器」还有 4 处（`GenerationAgentTurnPolicy:36`、`HeavyGenerationFinalizationService:62`、`GenerationTaskRequest:22`、`GenerationModeRouter:37`）。
**建议统一原则**：兼容构造器不得以 `null` 替代协作者 —— 否则生产行为与测试行为分叉，`GenerationCompletionPolicy` 就是活例子。

---

## 4. 关于「DAG 编排」的定性纠正

`GenerationDagRunner` 命名与实现不符，需要如实记录：

它计算了 ready-set，但用串行 for 循环消费：

`GenerationDagRunner.java:89-96`
```java
List<GenerationAgentNode> readyNodes = nodeMap.values().stream()
        .filter(node -> !scheduled.contains(node.key()))
        .filter(node -> completed.containsAll(node.dependencies()))
        .toList();
...
for (GenerationAgentNode node : readyNodes) {      // 串行
```

而且当前 7 个节点的依赖是**一条直链**，`readyNodes` 恒为单元素：

```
planner → template → context → architect → code → review → buildfix
```
（依据各节点 `super(...)` 声明：`PlannerAgentNode:29` deps=[]，`TemplateAgentNode:32` deps=[planner]，
`ContextAgentNode:40` deps=[template]，`ArchitectAgentNode:22` deps=[planner,context]，
`CodeAgentNode:28` deps=[architect]，`ReviewAgentNode:36` deps=[code]，`BuildFixAgentNode:21` deps=[review]）

**结论**：改调度器为并发**当前收益为零** —— 图本身没有可并行的分支。
真正的顺序是：**先放松假依赖，再谈并行**。可立即验证的一处：`ContextAgentNode` 依赖 `template`，
但读取上下文/语义索引与模板物化之间是否存在真实数据依赖，值得复核 —— 若无，`context` 与 `template` 可并行，
这才是让 `readyNodes` 真正出现多元素的前提。

**本轮不建议动调度器**，只建议把这个命名与现状的落差记录下来，避免后续误判为「已具备并行编排能力」。

---

## 5. 实施顺序建议

| 序 | 项目 | 预计工作量 | 见效 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | P1-5 产物同源 CSP/独立域 | 0.5 天 | 立即消除数据泄露风险 | **已落地** |
| 2 | P0-1 只读工具并发 | 1~2 天 | 工具阶段耗时降 40%+ | **已落地** |
| 3 | 第 3 节清理项（含完成门禁后门） | 0.5 天 | 消除生产/测试行为分叉 | **已落地** |
| 4 | P0-2 provisional 首屏预览 | 2~3 天 | 修正 TTP 指标失真；EXPERT 路径提前可预览 | **已落地（范围有修正）** |
| 5 | P1-4 结构感知压缩 + token 窗口 | 3~5 天 | 降低重复改动与修复轮次 | **已落地（含对本蓝图的自我修正）** |
| 6 | P0-3 意图澄清小模型兜底 | 3~5 天 | 模型档位准确率，省时省钱 | **已落地（默认关闭，含两处蓝图修正）** |
| 7 | P0-2 后续项：dev server 空闲回收 + 陈旧工作区收口 | 0.5 天 | 消除进程/端口/配额泄漏与「静默过期预览」 | **已落地（所有权上提仍待排期）** |

---

## 5.1 第一批落地记录

基线 `ce4bcc7`。全量 `bash ./mvnw -o test`：**2557 通过 / 0 失败 / 1 跳过**（基线 2545 通过）；
`bash ./mvnw -o -Pgeneration-release-smoke test`：**8/8 通过**。

### P1-5 产物 CSP

新增 `infrastructure/security/GeneratedContentSecurityPolicy`，作为两条产物通路的**唯一**策略来源：

- 已部署产物（`StaticResourceController`）：`sandbox`（不含 `allow-same-origin`）+ `connect-src 'none'`；
- dev-server 预览（`DevServerProxyService`）：同样 sandbox，但 `connect-src ws: wss:`。

**为什么预览要放通 WebSocket**：sandbox 下文档获得不透明源，`connect-src 'self'` 不匹配任何来源，
沿用 `'none'` 会拦掉 Vite 的 `vite-hmr` WebSocket，预览失去热更新 —— 正好损害本蓝图要改善的预览体验。
真正要防的「生成脚本以受害者身份调用平台接口」已由不透明源阻断（Cookie 不再随行），
`connect-src` 在此只是纵深防御，因此放通 ws/wss 的额外暴露面很小。HTTP 外发（fetch/XHR）仍被阻断。

代理侧用 `setHeader` 而非 `addHeader`：用户 vite 配置可能自带宽松 CSP，平台策略必须覆盖而非叠加。

**连带修复（原蓝图未预见）**：严格 sandbox 下 `localStorage` 抛 `SecurityError`，而三个 vue 模板
在**模块顶层**访问它（pinia persist、token 读取），会直接白屏。因此：

- 新增 `src/lib/safe-storage.ts`（3 个模板各一份）：探测真实存储可用性，沙箱下退化为内存存储；
- 7 处 `localStorage` 全部改走 `safeLocalStorage`；
- 3 个 codegen 提示词补上「禁止直接访问 localStorage」硬约束（模型生成新代码时同样适用），
  并同步 `prompt-catalog.json` 的 sha256 指纹。

> 指纹注意：目录校验的是 `PromptDigest.normalizeContent()` 后的内容（换行归一 + `stripTrailing`），
> 不是文件原始字节，`sha256sum` 直接算会不匹配。

### P0-1 只读工具并发

拆成两个单一职责的模块，而非在 945 行的 Runtime 里塞并发逻辑：

- `ToolBatchExecutionPlanner`：只决定「怎么分段」。连续 `READ_ONLY` 聚成并发段，
  写/破坏性/外部副作用工具单独成段并保持相对顺序，因此「先读后写」「写后再读」的语义与串行完全一致。
  工具未注册或风险等级未知时按非只读处理（保守优先）。单个只读请求不付并发开销。
- `ToolBatchExecutor`：只负责执行与**按原序回填**结果。

三处顺序语义被显式保住：`tool_call` 事件先按原序发出；`memory` 中 `ToolExecutionResultMessage` 按原序追加；
`returnBehaviors` 按原序收集，`shouldReturnImmediately` 语义不变（`ExitTool` 是 `READ_ONLY` 且
带 `IMMEDIATE_IF_LAST`，顺序若被打乱会误判）。

`MonitorContext` 是 ThreadLocal，不会自动传播到虚拟线程；若不显式安装，并发工具会丢失
userId/appId/taskId 归因，**token 计量与成本归属随之失真**。执行器逐线程安装快照、执行后恢复。

审批恢复路径（`resolveInterruptedToolRound`）**刻意保持串行**：它逐项检查取消、恢复的是单个已审批的
破坏性工具，并发化风险高、收益近零。

### 第 3 节清理

- 删除 `GenerationCompletionPolicy()` 无参构造。原先它把 fence guard 置 `null`，
  使 `runtimeOwnershipValid` **静默跳过栅栏校验** —— 且 `GenerationPipelineExecutor` 与
  `HeavyGenerationFinalizationService` 的兼容构造器**在生产代码里**调它，不只是测试。栅栏校验现为构造期强依赖。
- 删除 `GenerationPipelineExecutor` 两个兼容构造器，及其连带的 4 处 `!= null` 防御分支
  （那些分支只为容忍兼容构造器传入的 `null`，Spring 路径下恒真）。
- 删除 `HeavyGenerationFinalizationService` 兼容构造器、`GenerationTaskOrchestrator.getStream()`（无调用方）、
  `GenerationWorkspaceReleaseService.release()`（`@Deprecated` 纯委托）。
- `EditWorkspaceTransactionArchitectureTest` 原本断言 `release()` 委托存在；改为断言该方法**不存在**，
  更贴合该门禁的原意（唯一发布入口必须是语义明确的 `releaseVerified`）。

净变化 260 增 / 196 删，新增 5 个测试类共 11 个用例。并发与 CSP 两组测试都做过**反向验证**：
临时把并发段退化为串行、临时给 sandbox 加回 `allow-same-origin`，对应测试确实失败，
确认它们不是恒真断言。

---

## 5.2 第二批落地记录：P0-2 首屏预览（含对本蓝图的自我修正）

全量 `bash ./mvnw -o test`：**2562 通过 / 0 失败 / 1 跳过**；发布冒烟 **8/8**。

### 先修正原蓝图的一处判断错误

原文第 2 节称「provisional 预览：工作区首次具备可渲染入口即发布」。**这个方案是错的**，
因为它与 `GenerationExecutionWorkspace` 的类注释不变量直接冲突：

> 工作空间路径永远不是用户可见的应用程序路径。它可能只会变得可见
> 生成并验证完成后通过围栏发布服务。

「模板物化完成即发布」意味着把未验证、甚至还没写入任何业务代码的工作区暴露给用户，
必须打穿这条不变量并重新设计 dev-server 生命周期归属，工作量远超原估的 2~3 天。

**实际采用的方案**：不打穿不变量，而是复用已有事实 —— EXPERT 路径的运行时验证**本来就会**
针对隔离工作区启动一个真实 dev server（`DevServerValidationService` + `DevServerStartOptions.executionFence`）。
在「dev server 已就绪、验证尚未结束」这个既有窗口内发一次暂定预览通知即可，不新增任何暴露面。

### 用户提出的三个风险，实际如何解决

1. **未验证代码对用户可见** —— 未打穿不变量。暂定预览复用的是既有验证期 dev server，
   该暴露窗口在本次改动前就已存在（`requireRunningRoute` 只校验 `state == RUNNING`，从不校验 fence）。
   本次改动只是**如实告知用户**这个窗口的存在，没有扩大它。
2. **失败/回退时预览指向废弃纪元** —— 结构性有界：`DevServerValidationService` 在 `finally` 中
   `stopOwnedSession`，会话被标记为非 RUNNING，而用户预览路由要求 `state == RUNNING`。
   纪元作废时预览窗口随之关闭，无需额外栅栏校验。
3. **端口/进程生命周期跨越任务边界** —— 同上，生命周期仍完全由验证服务的 `finally` 拥有，
   本次改动没有延长任何进程存活时间。

### 改动内容

- 新增 `GenerationPreviewLevel{PROVISIONAL, VERIFIED}`；`GenerationFirstPreviewMilestone` 携带等级
  （保留旧构造器，默认 VERIFIED）。
- `GenerationExecutionContext` 新增 `firstProvisionalPreviewAt`，与 `firstPreviewReadyAt`
  **相互独立**：前者度量体验，后者度量交付。
- `DevServerValidationService.validate(...)` 增加 `onDevServerReady` 回调重载。
  回调点刻意选在「已就绪、`finally` 尚未 stop」的窗口内 —— 若等验证返回后再通知，
  用户拿到的预览地址已失效。回调异常被吞掉并记录，不影响验证结论。
- `DevServerValidationResult.startedSuccessfully()`：比 `isPassed()` 宽松，`RUNTIME_ERROR`
  也算已启动（进程已起、页面可访问，只是有控制台报错），用于判定体验事实而非交付结论。
- **修正 TTP 指标失真**（本项真正的核心价值）：`GenerationPipelineExecutor` 与
  `HeavyGenerationSessionCompletionService` 的 `resolveFirstPreviewMillis` 改为优先取暂定预览时刻。
  此前该字段恒等于任务总时长，作为 SLA 指标没有诊断意义。
- SLA 结论每任务仍只出一条：优先由暂定预览裁定，未产生暂定预览的链路（LIGHT_EDIT、HTML）
  由已验证预览兜底上报，避免指标缺失。

**安全边界已验证**：`grep` 确认 `orchestration/attempt/completion/` 与 `orchestration/lifecycle/`
中零处引用 provisional —— 暂定预览在结构上无法满足完成门禁、无法触发计费、无法写任务终态。

### 本项的实际收益边界（不夸大）

- **确定收益**：TTP 指标从「恒等于总时长」变为真实可诊断；EXPERT 路径用户在运行时验证阶段
  即被告知可预览，而非等到全部验证与发布完成。
- **不适用范围**：CREATE / AGENT_EDIT / LIGHT_EDIT 的 `ExpectedValidationLevel` 是 BUILD 或 FAST，
  按 `requiresRuntimeValidation` 不启动 dev server，因此**不会**产生暂定预览。
  原文承诺的「用户感知首屏从分钟级到秒级」仅在 EXPERT 路径成立，其余路径不变。
  要覆盖全部路径，仍需正面解决工作区可见性不变量 —— 那是一次独立的架构决策，不在本批范围内。

---

## 5.3 第三批落地记录：P1-4 结构感知压缩 + token 窗口

基线 `ce4bcc7`。全量 `bash ./mvnw -o test`：**2611 通过 / 0 失败 / 1 跳过**（本批新增 49 个用例）；
`bash ./mvnw -o -Pgeneration-release-smoke test`：**8/8 通过**。

### 先修正原蓝图的一处事实错误

第 2 节称「Agent 对话窗口只有 12 条消息」，并引用 `DefaultGenerationAgentRuntime` 的
`HEAVY_CHAT_MEMORY_MESSAGES = 12`。实际读代码后发现**主链路更窄**：

`DefaultGenerationAgentRuntime` 的 12 条只用于**审批恢复/续跑**路径；
首次生成走 `GenerationAgentConversationInitializer`，窗口是 `MAX_MEMORY_MESSAGES = 8`。
即「一轮 assistant + N 个 tool_result」的口径下，主链路只有 3~4 轮工具循环，
比蓝图描述的更容易触发静默丢弃。两处窗口互不知晓，也是这类常量分散的典型代价。

### 改动内容

1. **结构感知压缩**（`ContextBlockSplitter` / `ContextBlock` / `StructureAwareContextCompressor`）
   按代码围栏、Markdown 标题、文件条目边界切块，超预算时**整块丢弃**并保留一行摘要（路径 + 行数），
   不再用 `substring` 把 JSON 与 `import` 切成语法碎片。

2. **折叠式 token 窗口**（`FoldingTokenWindowChatMemory` / `AgentConversationFolder` /
   `AgentToolRoundSummary` / `ToolRoundPathExtractor` / `AgentConversationTokenAccountant`）
   溢出时把最早的工具轮折叠为一条系统摘要（已读 / 已写 / 已删文件 + 未解决的构建错误），
   而不是丢弃 —— 即 Claude Code 的 compaction 思路。折叠**只在轮次边界发生**：
   留下没有对应 `AiMessage` 的 `tool_result` 会被 OpenAI 兼容服务端整单拒绝。

3. **窗口口径从「消息条数」改为「token 预算」**（`AgentConversationWindowPolicy`）
   生成 48k / 续跑 32k，取代原先的 8 条 / 12 条。token 会计把**工具参数与工具结果一并计入**，
   否则「消息条数正常但参数巨大」（一次 writeFile 写入整份源码）可以合法绕过窗口。
   按既定配置约定，该策略类是唯一预算来源，并已登记进发布配置指纹。

### 过程中发现并修复的两个真实缺陷

- **压缩器越界**：省略摘要的开销原先只从尾部预算扣除，尾部为空时摘要会把结果重新顶出预算
  （实测预算 484 下输出 613 字符）。改为先从总预算扣除，摘要自身按条目上限收口，
  并加一道「仍越界则退化为单块截断」的兜底。
- **折叠边界定位错误**：`Round` 是 record，`equals` 按值比较，用户重试同一诉求产生值相同的轮次时，
  `indexOf` 会命中错误位置 —— 表现为「宣称折叠 3 轮，实际只折叠 1 轮，预算并未下降」。
  改为按引用定位。此项由代码审查发现，非测试失败暴露。

### 回归测试与反向验证

新增 6 个测试类共 49 个用例，逐个做了**反向验证**（临时破坏实现，确认用例以预期信息失败后再还原）：

- `ToolRoundPathExtractorTest` 不复制工具名字面量，而是走 LangChain4j
  `ToolSpecifications.toolSpecificationFrom` 的真实推导链路取协议字段名比对。
  工具改名、参数改名、或编译期 `-parameters` 被关闭都会失败 ——
  否则名称漂移的后果是折叠摘要静默变空，模型把已写好的文件重写一遍，全链路不报错。
- 反向验证过程本身补出一个测试盲区：把写入路径的预算收敛去掉后，12 个用例**全部照样通过**，
  因为 `messages()` 读取时会兜底收敛，掩盖了写入路径失效。已补
  `budgetMustBeEnforcedOnWriteNotOnlyOnRead` 直接断言落库内容 —— 该缺陷的真实后果是
  共享存储随会话无界增长，且每次读取都要对全量历史重新分词。

### 本项的实际收益边界（不夸大）

- **确定收益**：主链路窗口从 8 条消息（3~4 轮工具循环）扩到 48k token，
  且溢出轮次以摘要形式保留而非丢弃；压缩不再产出语法碎片。
- **未实测**：「重复改动与修复轮次下降」需上线后用 `repairRounds` 指标校准，
  本批只证明了机制正确，没有真实模型对照数据。
- **明确的设计取舍**：折叠到只剩一轮仍超预算时，本实现**保持原样**并把问题暴露给调用层，
  而不是截断出一份语义已被破坏的对话。此时模型调用会被服务端拒绝 —— 这是有意为之的失败方式。

---

## 5.4 第四批落地记录：P0-3 意图澄清小模型兜底（含对本蓝图的两处修正）

基线 `ce4bcc7`。全量 `bash ./mvnw -o test`：**2627 通过 / 0 失败 / 1 跳过**（本批新增 16 个用例 + 1 个架构测试）。
特性默认关闭（`AI_INTENT_CLARIFICATION_ENABLED:false`），先按开关灰度再考虑默认开启。

### 先修正原蓝图的两处判断错误

**其一：「低置信时兜底」这个门禁条件不成立。** 原文第 121 条要求「低置信时补一次小模型分类」，
但读完 `IntentProfileService` 后确认 `confidence` 是**加性常量分**：每命中一类关键词就加一个固定权重，
命中越多分越高。于是「自信但判错」的请求（关键词表恰好命中了错误的那一类）分数与判对时**完全一致**，
实测同为 0.80；而真正含糊的请求因为一条也没命中，分数反而**不低**（起评分兜底）。
按置信度开闸，会精确地漏掉最需要兜底的那批请求。

改法：门禁换成与置信度无关的**来源信号**（`IntentAmbiguitySignal` / `IntentResolutionDimension`）——
记录每个维度是「关键词命中」还是「走了默认分支」，只有**多个维度同时落在默认分支**才触发澄清。
这是可判定的事实，不是评分。

**其二：「先以 challenger 身份灰度」不适用。** 原文第 126 条建议复用 `GenerationShadowRoutingService`
做 A/B。但通读全部路由策略后确认：**没有任何一条冠军路由策略读 `IntentProfile`**。
`IntentProfile` 的唯一真实消费者是 `GenerationExecutionPlanner`，且只取其中两个 boolean
用于选模型档位。影子路由比对的是「候选引擎按画像会怎么选路由」，与主链路实际用法无交集，
拿它灰度澄清效果等于测量一条不存在的因果链。

同一结论也否掉了本项原定的「加宽 challenger SPI 以传入原始文本」：影子评估跑在**提交线程**上，
没有任务级预算与取消栅栏，让它接触原文只会诱导实现在此处调模型，把观测链路变成第二条计费链路。
`GenerationShadowRouteChallenger` 保持 `decide(IntentProfile)` 不变，理由已写入其 javadoc。

### 澄清阶段的落点（这是本项唯一真正的设计决策）

既然澄清只能影响模型档位，它就不必发生在提交线程：

```
提交线程：关键词解析 → 路由 → 冻结 ExecutionPlan（SLA 在此定死）→ 入队
任务线程：IntentClarificationStage（澄清 + 只重算模型档位）→ 路由回退循环 → Agent
```

放在任务线程，`taskId` 与带预算的 `GenerationExecutionContext` 都已就位，模型调用天然受预算与取消约束；
放在**路由回退循环之前**，一个任务最多澄清一次，回退重试不会重复付费；SLA 保持提交时冻结不变。

三道闸门保证澄清无法扩大自己的作用域：

- `IntentClarificationRefiner` 只采纳**本地解析未定**的维度，`destructiveRisk` / `scopes` /
  `requiresDatabase` 一律原样保留，验证风险**只升不降**，并拒绝把操作类型改成 `CREATE`
  （首次生成由工作区状态唯一决定，不容模型改写）；
- `GenerationPipelineRequest.withRefinedIntent` 拒绝任何改变路由决策的精化结果；
- `GenerationSession.rebindRefinedExecutionPlan` 用 `withModelProfile` 反推期望计划做等值比对，
  只放行「仅模型档位变化」，其余一概抛错。

模型调用沿用 `HeavyGenerationTargetTypeRouter` 既有范式：消耗 `ROOT_MODEL_ATTEMPT` 预算、
`clampTimeout` 收口超时、span 记 `degraded`、失败即回落本地画像。
`GenerationExecutionPolicyException`（预算耗尽 / 任务终止）是唯一必须上抛的异常；
其余异常先 `assertCanContinue()` 再降级，避免把「任务已取消」误报成「澄清失败」。

### 顺带修掉的两个真实缺陷

- **破坏性关键词漏判**：`删除所有` / `所有删除` 这类语序变体不在关键词表内，
  高破坏性请求会按低风险放行。已补入表并加用例。
- **脆弱断言**：`IntentProfileServiceTest` 原先钉住 `IntentProfile` 的字段数量（`length == 9`），
  任何合法新增字段都会误伤，却挡不住「把某个字段悄悄换成提示词副本」这一它本想防的事。
  改为直接断言「不存在任何 `CharSequence` 类型字段」——即原始诉求不得被画像携带。

### 顺手清掉一处第 3 节遗留的死代码

`GenerationContextCompressionService.compressMemoryContext` 全仓零调用方。它不只是没人用：
记忆上下文实际由 `AiContextPackBudgeter` 在装配期按 **token** 预算收口，
而该方法按 **字符** 预算再压一遍——留着它，迟早有人接回去，让同一段文本受两套互不知晓的预算约束。
已连同 `MEMORY_CONTEXT_BUDGET` 常量一并删除，并在接口 javadoc 写明记忆上下文不属于本契约。

### 补上一处测试盲区：重复 Bean 只会在生产启动时炸

`GenerationShadowRoutingSpringWiringTest` 用 `ApplicationContextRunner` +
`withUserConfiguration(...)` **逐个列举** Bean，因此扫描到的第二个实现它根本看不见，
`hasSingleBean` 形同虚设；而整包启动测试带 `@Tag("external")`，默认构建里被排除。
结果是「同一 SPI 注册了第二个实现」这类错误只能在生产启动时以
`NoUniqueBeanDefinitionException` 暴露。

新增 `SpringSingleValuedInjectionAmbiguityArchitectureTest`：对生产类做静态推导，
把「被单值注入的自有接口」与「无条件注册的候选实现数」交叉比对，覆盖构造器注入、
`@Bean` 工厂方法（含其参数）与字段注入，`@Primary` 与 `@Conditional` 家族视为已消歧。
无白名单，新增 SPI 或新增实现自动纳入校验。已做反向验证：临时加入第二个
challenger 实现后该用例以预期信息失败，删除后恢复通过。

### 本项的实际收益边界（不夸大）

- **确定收益**：多维度落默认分支的请求（「把登录页做得好看点」这类）不再静默按默认档位执行，
  而是走一次结构化澄清；安全维度不可被澄清下调；澄清失败/超时/取消不影响主链路。
- **未实测**：路由与档位准确率的实际提升需要开关打开后用线上样本校准，
  本批只证明机制正确与边界安全，没有真实模型对照数据。
- **明确的作用域限制**：澄清**不改变路由**，只改变模型档位与工具调用上限。
  想让画像影响路由，前提是先让路由策略真正消费 `IntentProfile` —— 那是另一项改动，不在本批。

---

## 5.5 第五批落地记录：P0-2 后续项的两个前置缺陷（dev server 生命周期）

基线 `ce4bcc7`。全量 `bash ./mvnw -o test`：**2640 通过 / 0 失败 / 1 跳过**（本批新增 12 个用例）；
发布冒烟 `bash ./mvnw -o test -Dgroups=generation-release-smoke`：**8/8 通过**。

第 6 节把「所有权从验证方法作用域上提到任务作用域」列为 P0-2 的后续项。动手前先去核对它的前置条件，
结果发现两件事：所需的配套机制**一个都不存在**，而且它们各自就是独立缺陷 —— 与所有权上提无关也该修。
本批只修这两个前置缺陷，**所有权上提本身仍未做**（理由见末尾）。

### 缺陷一：全仓没有任何空闲回收

`maintainSessionLeases` 每 10s 巡检一次，但只做续租，只在 `STOP_REQUESTED` / 租约 `LOST` 时停；
`DevServerSessionRecoveryService` 只清理**已过期**的租约（即已经无人续租的死会话）；
`DevServerSessionRecord` 也没有「最后访问时间」这一列。合起来的后果是：只要有一次 stop 没走到
（任务异常退出、用户直接关掉预览页），这个会话的 Vite 进程、端口、以及该用户 3 个会话配额中的一个，
会**一直占用到 JVM 退出**。这不是「上提所有权之后才会有的问题」—— 现状下每一次漏掉的 stop 都在泄漏。

### 缺陷二：`requireRunningRoute` 会把流量转给工作区已被移走的 dev server

发布流程用 `pathMover.move(source, destination)` 把执行工作区**整体移动**到版本目录
（`GenerationWorkspacePublicationService:270`）。Linux 上 Vite 进程仍持有旧 inode，进程照常存活、端口照常监听，
于是路由校验全部通过、预览页正常打开 —— 但内容永久停在移动前那一刻。**用户看到的是一份无声的过期内容**，
既不报错也不更新。这比直接失败更糟：失败会被上报，静默过期只会被当成「AI 没改对」。

### 改动内容

两个缺陷都收口在**回收侧**，而不是在路由侧加判断：巡检本来就持有这份资源，停掉进程既解了泄漏，
又让后续路由自然报「无运行中会话」这个诚实结果。一处改动同时修掉两件事，且不引入新的调度器。

- `DevServerRuntimeProperties.IDLE_SESSION_TIMEOUT = 20min`（Java 常量，随
  `HARDCODED_POLICY_SOURCES` 既有登记自动进入发布指纹，无需改注册表）。取值需同时容纳
  用户读代码切窗口的浏览器静默、与生成链路两轮修复之间的空档。
- 空闲巡检**折叠进既有心跳** `maintainSessionLeases`，顺序是「先判可回收，再续租」——
  颠倒会让本轮就该回收的会话反被续上一个租期。该顺序由用例钉住。
- 回收判据两条：工作区目录已不在原处（`Files.isDirectory(..., NOFOLLOW_LINKS)`），
  或静默超过空闲上限。只处理已进入运行态的会话（启动中的由启动线程自行收口）。
- 活跃度记账落在**流量到达所有者节点**的两个点：`DevServerPreviewRoutingService` 的本地分支
  （本地路由直连回环、不经内部跃点，单节点部署全靠这里）与 `requireLocalRunningPort` 校验通过后
  （跨节点预览唯一的活跃度来源）。远端转发分支**刻意不记账**，否则同一次访问会记两遍，
  且非所有者节点会为它管不到的会话续命。
- 空闲判定用 `System::nanoTime` 而非 `java.time.Clock`：这里问的是「过了多久」不是「几点」，
  墙钟回拨会让基于 `Instant` 的判定瞬间误杀活跃会话。测试注入可控时间源，用例因此**不含 sleep**。
- 配置校验新增 `isIdleSessionTimeoutSafe`：空闲上限必须大于心跳间隔，否则一次巡检就可能误杀刚建立的会话。

### 过程中发现并修复的一个假绿

`DevServerManagerDurableLeaseTest` 把 `project` 指向 `target/durable-dev-server-test` 却从未创建该目录
（`ls` 确认不存在）。本批加入「工作区消失即回收」之后，
`lostLeaseMustFenceAndStopTheLocalProcess` 会**经由新的 `workspace_directory_missing` 分支**通过，
而不是经由它名字所指的租约丢失路径 —— 用例仍然绿，但已经不再测它声称要测的东西。
已在 `setUp` 建目录，并补 `verify(leaseCoordinator).renew(11L)` 让用例自己钉住通过原因。

### 回归测试与反向验证

新增 `DevServerManagerIdleReclamationTest`（6 例）+ `DevServerPreviewRoutingServiceTest` 4 例
+ `DevServerRuntimePropertiesTest` 2 例。反向验证做了两次：把
`if (reclaimIfUnusable(session))` 短路成 `if (false && ...)` → 3 失败 / 3 通过，
通过的 3 例正是「不应回收」的负向用例，证明断言非空转；摘掉两条新配置约束 → 对应 2 例失败。
两次均已还原。

### 本项的实际收益边界（不夸大）

- **确定收益**：漏掉 stop 不再永久泄漏进程 / 端口 / 会话配额；工作区被发布移走后预览不再静默返回过期内容。
- **仍未做**：**所有权上提本身**。它要把 dev server 的持有者从验证方法的 `finally` 改成任务生命周期，
  是一次跨多文件、作用于共享资源的生命周期重构，还需要 `requireRunningRoute` 补纪元（fence）校验。
  其设计与风险边界应先评审再落代码 —— 本批只是把它的两个前置条件补齐了。
- **未实测**：20min 这个取值是否贴合真实用户行为，需上线后看回收日志中 `idle_timeout` 与
  `workspace_directory_missing` 的实际分布校准。

---

## 6. 未覆盖声明

第一批落地后仍未验证的部分：

- **前端模板未做 typecheck**：模板目录无 `node_modules`，离线环境无法执行 `vue-tsc --noEmit`。
  `safe-storage.ts` 与 7 处改动只做了语法与 `@/` 别名解析核对（`vite.config.ts` 中 `@` → `src`），
  并确认文件已进入 `target/classes` 构建产物。**建议联网后先跑一次模板构建再上线。**
- **CSP 未在真实浏览器验证**：sandbox 对 HMR、visual editor `postMessage`、iframe 下载行为的实际影响
  仅从代码推断（前端已用 `<iframe sandbox>` 且不含 `allow-same-origin`，visual editor 走 `postMessage`
  而非直接 DOM 访问，理论上兼容）。**建议手工验证一次预览热更新与可视化编辑。**
- 只读工具并发的**实际提速倍数**未实测，40%~60% 是基于「模型批量调用比例」的估计，
  需上线后用 `GenerationSpanCategory.TOOL` 分段耗时校准。
- 暂定预览**未做端到端验证**：回调时序、幂等与安全边界都有单测覆盖，但「用户在验证阶段
  确实能打开预览页面」需要一次真实 EXPERT 任务确认。
- **暂定预览的可用窗口只有约 5 秒**（`DevServerRuntimeProperties.VALIDATION_ERROR_COLLECTION_WINDOW`
  = 5s，之后 `finally` 立即 stop dev server）。这意味着当前形态下，用户几乎来不及真正点开预览 ——
  **本批的实质收益是 TTP 指标不再失真，而不是用户体验已经改善**。
  要让暂定预览真正可用，必须把验证期 dev server 的所有权从「验证方法作用域」上提到「任务作用域」，
  并配套空闲回收与纪元校验（`requireRunningRoute` 目前不校验 fence）。这是一次独立的
  生命周期重构，应作为 P0-2 的后续项单独排期，不要误以为本批已完成该体验目标。
  **进展（第五批）**：其中的**空闲回收已落地**（见 5.5），顺带修掉了「工作区被发布移走后仍路由到旧
  dev server、预览静默过期」这一独立缺陷。**所有权上提与 fence 校验仍未做**，5 秒窗口的结论不变。

- **意图澄清未在真实模型下验证**：`AI_INTENT_CLARIFICATION_ENABLED` 默认 `false`，
  澄清链路的预算消耗、超时降级与采纳边界均由单测覆盖，但「小模型对含糊中文诉求的实际判准率」
  需开关打开后用线上样本校准。**建议先在灰度环境开启并观察 `intent_clarification_model` span
  的 degraded 比例，再考虑默认开启。**

以下部分本轮未逐行追溯，不在上述结论的置信范围内，如需请单独排期：

- `WorkspaceFileSystemService`（1281 行，全仓最大文件）的事务原子性与并发发布竞态；
- `DevServerManager` / `ManagedProcessExecutor` 的进程树泄漏与僵尸端口 ——
  第五批已补上「会话级」回收（空闲 + 工作区消失，见 5.5），但**进程树本身**是否被完整杀干净
  （子进程、esbuild 等孙进程、`SO_REUSEADDR` 残留端口）仍未追溯；
- `HedgedStreamingExecution` 与 `FailoverStreamingChatModel` 的取消传播是否真正中断上游 HTTP 流；
- SSE 跨实例重连（本地会话优先于 Redis 流，多实例部署下 `GenerationSessionRegistry` 为单机内存）——
  `GenerationTaskQueryService:149-159` 先查本地会话再回落持久流，多实例场景需单独验证；
- 656 个测试文件未执行（本轮只做 `compile`），文中所有结论均来自静态代码追溯而非运行时验证。
