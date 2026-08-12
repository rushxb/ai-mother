# 后端 AI 生成项目链路审计与优化蓝图 v2

审计日期：2026-08-12
代码基线：`dev@ba73348`，并纳入当前工作区未提交的 Dev Server / 工作区发布改动
审计范围：生成任务入口、场景路由、任务准入与队列、Agent 编排、模型与工具循环、工作区发布、预览、取消/恢复、积分、安全、质量评测
审计方式：静态代码追踪与测试覆盖核查；未使用线上流量数据，性能收益需通过灰度验证

> `docs/AI生成链路优化蓝图.md` 记录了前序优化及落地历史。本文件只描述当前基线仍存在的核心问题，不重复建议已经完成的 CSP、只读工具并发、上下文压缩、暂定预览等改造。

---

## 1. 结论

当前项目的工程基础已经明显高于普通 AI 代码生成项目：持久任务、幂等提交、积分预授权、租约与执行栅栏、工作区隔离发布、模型容灾、工具审批、容器沙箱、发布评测门禁均已具备。

真正限制下一阶段用户体验和生成质量的，不是缺少更多 Agent 或更多基础组件，而是三个控制面尚未收口：

1. **终态控制面不唯一**：文件发布、应用状态、Trace、积分、持久任务和用户事件分段提交，存在互相矛盾的中间态。
2. **场景决策面不唯一**：线上关键词策略、结构化意图画像、影子路由三套规则并存，反馈无法按场景归因并推动晋级。
3. **质量学习面不闭环**：评测能验证产物，但不能验证“是否选对链路”；反馈能保存，但不能回答哪条路由、哪个模型或提示词版本造成低分。

一句话判断：**底座已接近成熟工程，Agent 产品内核仍需要从“组件齐全”升级为“决策可学习、终态可证明、收益可评测”。**

---

## 2. 当前链路

```text
POST /generation/tasks
  -> 登录态 / 应用所有权 / 限流 / Idempotency-Key
  -> IntentProfile + 线上路由策略
  -> 冻结 SLA / 模型档位 / 工具与修复预算
  -> 积分预授权 + 持久任务命令
  -> 本地或 Redis 队列
  -> worker 租约 + execution fence + 隔离工作区
  -> CREATE | LIGHT_EDIT | AGENT_EDIT | HEAVY_EXPERT
  -> 模型 / 工具循环 / 审批暂停与恢复
  -> 构建、运行时验证、暂定预览
  -> 完成证据门禁
  -> 工作区发布
  -> 应用状态 / Trace / 积分 / 持久任务 / SSE 终态
  -> 结果记忆、反馈、评测
```

这条主链路的入口和隔离边界是清楚的。问题主要集中在最后三步，以及路由决策如何从真实结果中学习。

---

## 3. 已有优势：本轮不要重复造轮子

| 能力 | 判断 | 代码依据 |
| --- | --- | --- |
| 提交与并发控制 | 良好 | `GenerationTaskAdmissionService` 在同一事务中检查用户并发、预留积分并持久化可重建命令 |
| 执行所有权 | 良好 | `GenerationTaskLeaseCoordinator` 通过租约、owner、execution epoch 和 fence 防止旧 worker 提交新纪元结果 |
| Agent 工具治理 | 良好 | 工具风险分级、只读并发、写入串行、ChangePlan、循环检测、生产力守卫、人工审批与续执行均已存在 |
| 工作区发布 | 良好 | `GenerationWorkspacePublicationService` 使用版本目录、活动指针、发布 journal 和回滚补偿 |
| 模型与成本 | 良好 | 模型档位、根调用/turn/failover/repair/tool-write 预算、预授权与按 token 结算均已具备 |
| 安全 | 良好 | 生产强制容器沙箱；生成页面 CSP；预览鉴权代理；长期记忆按不可信历史隔离 |
| 质量门禁 | 良好 | 32 条端到端生成基准，覆盖结构、功能、diff scope、安全、运行时、视觉、耗时、token 和积分 |

因此不建议再启动“重写 Agent 框架”“引入另一个工作流引擎”“增加更多微服务”这类大工程。它们不会直接改善当前最痛的用户问题。

---

## 4. 核心优化项

### P0-1：建立唯一的任务终态收口器

#### 确定缺陷

普通 Pipeline 成功路径依次执行：

1. 发布工作区：`GenerationPipelineExecutor:217-224`
2. 完成应用状态、Trace，并首次结算积分：`:226-248`
3. 写结果记忆和发送 `TASK_DONE`：`:250-258`
4. 最后才写持久任务终态，并再次尝试结算积分：`:441-464`、`GenerationTaskRuntimeLifecycleService:162-175`

这不是一个原子边界，存在以下真实失败窗口：

- 文件已经发布，业务生命周期事务失败，外层异常会把任务标为失败，形成“用户能看到新代码，但任务显示失败”。
- `TASK_DONE` 已发送，持久任务终态写入失败，刷新后任务仍是 `RUNNING`。
- `GenerationTaskLifecycleService.completeGenerationAndCharge()` 和 `GenerationTaskRuntimeLifecycleService.completeOwned()` 都调用积分结算；当前依赖幂等避免重复扣费，但仍有两次事务与两个责任所有者。
- 租约恢复扫描直接 `finalizeExpiredLease()` 并释放应用占用（`GenerationTaskRecoveryService:138-149`），没有同步完成 Trace、结果记忆和即时积分结算，只能等待定时积分对账兜底。
- Heavy 路径另有一套相似终态逻辑（`HeavyGenerationSessionCompletionService:51-94`），继续扩大行为分叉。

#### 建议设计

新增唯一应用层端口 `GenerationTaskFinalizer`，所有成功、失败、取消、超时、提交补偿、租约恢复、审批超时都只能提交一个 `GenerationFinalizationCommand`：

```text
taskId + executionFence + terminalStatus + reason
+ publicationResult / completionEvidence
+ outcomeQuality + memorySummary + usageSnapshot
```

收口规则：

1. 成功时先完成发布的可恢复准备，复用现有 publication journal；不要重写文件事务。
2. 在一个数据库事务中校验 fence，并提交持久任务终态、应用状态释放、Trace 终态、积分结算标记和 terminal outbox。
3. 事务提交后由 outbox 发布 SSE、写语义记忆和执行非关键清理；消费者必须幂等。
4. 文件发布与数据库不能组成真正 ACID 事务，采用现有 journal + 状态机补偿，明确 `PREPARED -> FILESYSTEM_COMMITTED -> FINALIZED`。
5. 删除 Pipeline 与 Heavy 各自的终态编排；`GenerationTaskRuntimeLifecycleService` 不再持有积分结算职责。

#### 用户价值

- 消除“代码成功但任务失败”“已提示成功但刷新仍运行”的高伤害体验。
- 取消、超时和 worker 崩溃都能立即释放积分并形成完整审计记录。
- 终态行为只有一个扩展点，新增渠道通知、成本策略或审计项不再修改多条链路，符合开闭原则。

#### 验收

- 对终态每一步注入异常，断言最终只能得到一种可恢复状态，不能出现发布指针与任务状态矛盾。
- 全仓除 `GenerationTaskFinalizer` / repository 外，不再直接调用 `completeOwned`、`completeUnowned`、`finalizeExpiredLease`。
- 单任务只产生一次积分结算事务；定时对账只处理极端中断，不再承担正常路径补偿。
- 指标：`terminal_inconsistency_total = 0`，终态 outbox P99 延迟小于 5 秒。

---

### P0-2：把场景路由收敛为一个结构化决策面

#### 确定问题

`GenerationModeRouter:46-68` 同时生成 `IntentProfile` 与线上 champion；在本轮改造前线上主要策略仍直接读取文本：

- `AgentEditRoutingPolicy:16-40` 等类维护各自关键词。
- `GenerationRoutingSignal.looksLikeSmallSingleFileEdit()` 另有一套动词与长度规则。
- `IntentProfileRoutingDecisionEngine:18-104` 用同一意图画像做另一套路由，但只作为 shadow challenger；本轮已将它提升为生产语义策略。
- 意图澄清默认关闭（`application.yml:93`），即使开启也只允许重算模型档位，不能纠正流水线路由。

结果是新增场景需要同时修改关键词、画像推导和影子规则；“看起来支持扩展”实际有多处事实源。更严重的是，低评分会按**整个应用**聚合后把后续请求升级 Heavy，而不是按本次需求场景判断（`GenerationTelemetryRoutingPolicy:29-37`）。一个历史失败的复杂需求可能让后续简单改文案也走最重链路。

#### 建议设计

保留 `GenerationRoutingPolicy` 扩展点，但统一输入和职责：

1. `IntentProfile` 成为唯一语义事实源，包含 operation、scope、complexity、destructive risk、validation risk、expected files、confidence、evidence。
2. 关键词只作为 `IntentEvidenceExtractor` 的低成本证据插件，不直接返回路线。
3. 一个 `GenerationRouteDecisionEngine` 负责从画像、工作区能力、负载与同类场景历史结果选择 route、validation、model tier、budget profile。
4. 低置信画像才调用小模型澄清；高置信本地规则直接返回，控制首字延迟与成本。
5. 路由结果持久化 `decisionVersion + evidence + alternatives`，便于回放和灰度，而不是只存最终 route。
6. 先 shadow 对比，达到晋级门槛后整体切换；不要长期维护两套 champion 规则。

#### 用户价值

- 简单请求不因历史复杂任务被无脑升级，减少等待与费用。
- 复杂、跨层、破坏性修改更稳定进入具备充分验证和预算的链路。
- 新增“移动端改版”“数据库迁移”“纯视觉调整”等场景只需增加画像证据或决策策略，不需要复制关键词表。

#### 验收

- 建立至少 100 条路由标注集，覆盖口语、短句、混合中英文、反向表达和连续编辑。
- Top-1 路由准确率不低于 90%；高风险请求误入 `LIGHT_EDIT` 的比例低于 1%。
- shadow 与 champion 的差异必须能关联最终成功率、评分、耗时、token、fallback 和 repair rounds。
- 简单编辑 P90 不劣化；复杂编辑一次成功率提升后才允许新决策器晋级。

---

### P0-3：把评测和反馈升级为“路由 + 产物 + 成本”闭环

#### 确定问题

当前基准很扎实，但此前验证的是指定 mode 下的生成结果，不验证入口是否选对 mode：

- `GenerationBenchmarkCatalog:24-30` 固定 32 条任务，只允许 `CREATE / LIGHT_EDIT / AGENT_EDIT`，没有 `HEAVY_EXPERT`；本轮已补充架构迁移样本。
- `GenerationBenchmarkRequestFactory` 只把 prompt 交给真实 orchestrator，数据集中的 `mode` 主要用于报告分组和 fixture 规则，没有“期望路由与实际路由一致”的门禁；本轮已增加 `expectedRoute/forbiddenRoutes` 和结果校验。
- `GenerationBenchmarkReleaseGate:29-120` 检查成功率、质量、耗时、预览、token 与积分，但不检查 route accuracy、错误升级率或错误降级率；本轮已增加三项路由门禁。
- 用户反馈只包含 task/app/user/rating/outcome/comment（`DefaultGenerationFeedbackService:63-94`）；路由侧只读取应用级任务和评分聚合（`DefaultGenerationRoutingTelemetryProvider:206-280`）。

这意味着系统知道“这个应用最近评分低”，但不知道是哪个场景、路线、模型、Prompt、工具失败或验证遗漏导致低分。

#### 建议设计

只补最小闭环，不引入复杂 MLOps 平台：

1. 给基准任务增加 `expectedRouteSet`、`forbiddenRoutes`、`expectedValidationFloor`、`maxCost`、`maxDuration`。
2. 新增 30~50 条纯路由/意图快速集，不运行完整生成；PR 中秒级执行。
3. 端到端集补齐 `HEAVY_EXPERT`、审批恢复、取消、worker 恢复、后端/全栈升级和连续多轮编辑。
4. 将 taskId 关联的 `route decision version / model fingerprint / prompt bundle / tool profile / fallback / repair` 纳入反馈分析视图。
5. 以场景桶而非 appId 聚合质量：`intent_signature + route + codeGenType + releaseVersion`。
6. 发布门禁增加非劣化约束：成功率、一次构建通过率、P90 TTP、P90 总耗时、平均 token、错误路由率。

#### 用户价值

- 每一次 Prompt、模型、路由或 Agent 改动都有可比较证据，避免“感觉更智能，线上反而更差”。
- 能快速定位低评分到底来自路由、生成、验证还是预览，缩短修复周期。
- 成本优化不再以牺牲成功率为代价。

#### 验收

- 每个生产 route 至少 10 条端到端任务、20 条路由任务。
- 报告必须输出 `routeConfusionMatrix`、场景成功率、一次构建通过率、fallback/repair 分布和单位成功任务成本。
- 模型、Prompt、路由版本没有签名基准证据时不得切换为生产 active。
- 低评分样本可一跳定位到完整 provenance，不再只看到 app 级平均分。

---

### P1-4：统一任务级资源清理，补齐暂定预览和失败工作区生命周期

#### 确定缺陷与风险

当前工作区改动将暂定预览声明为 `TASK_SCOPED`：验证返回后继续运行，成功发布前由 `GenerationProvisionalPreviewLifecycle` 精确停止。这解决了“事件发出后预览立即失效”的问题，但失败/取消/超时没有同一个显式停止点。

现状依赖两层兜底：

- 工作区目录消失后，Dev Server 心跳以 `workspace_directory_missing` 回收。
- 目录仍存在时，默认等待 20 分钟 idle timeout（`DevServerRuntimeProperties:40,105-112`）。

同时，worker 清理只执行 `executionWorkspaceService.clear(fence)`（`GenerationTaskCommandExecutionService:353-375`），而 `clear()` 只删除内存中的 scope 映射（`GenerationWorkspaceExecutionScope:148-159`），不会删除磁盘上的失败 execution workspace。

因此失败后可能同时保留 Dev Server 进程、端口、容器资源和执行工作区，直至空闲回收；诊断保留与资源释放也没有统一策略。

#### 建议设计

在 P0-1 的终态 outbox 中增加统一 `GenerationTaskResourceFinalizer`：

1. 按 `executionFence + workspace root` 精确停止暂定预览，绝不按 appId 误杀用户正式预览。
2. 清理 tool context、session、模型取消句柄、Dev Server、端口/容器和内存 scope。
3. 成功工作区由发布 journal 接管；失败工作区按策略进入 `QUARANTINED`，设置 TTL 后清理。
4. 仅对需要诊断的系统错误保留失败工作区；用户取消和准入/路由失败立即删除。
5. 清理步骤幂等并逐项记录，失败进入重试队列；idle timeout 只做最后兜底。

#### 用户价值

- 用户取消后立刻释放预览配额，下一次生成不会被残留会话阻塞。
- 保留必要诊断能力，同时控制磁盘、端口、进程和容器成本。

#### 验收

- 成功、失败、取消、超时、审批过期、worker crash 六类测试都断言任务资源最终归零。
- 正式用户预览不能被其他执行纪元的清理误杀。
- 终态后 30 秒内临时 Dev Server/容器为 0；quarantine 磁盘量和 TTL 可监控、可限额。

---

### P1-5：给“规则规划器 + 自主 Agent”瘦身，而不是继续增加 Agent 数量

#### 设计冗余

`AgentGenerationOrchestrator` 被描述为七节点多 Agent DAG，但当前节点大部分是确定性 Java 规则阶段：

- Planner 通过关键词/规则推断复杂度、模块和 API 领域。
- Architect 只是从上下文推断模块，并声明 `parallelizable`。
- Code 将制品拼成最终 prompt 和 ChangePlan。
- Review 在代码生成**之前**检查 prompt/plan 是否完整。
- BuildFix 在真实构建**之前**只生成修复策略。

随后 Heavy 路径才进入真正具备模型、文件工具、构建与修复能力的 `DefaultGenerationAgentRuntime`。`generation_spec` 和 `change_plan` 确实被后半段消费，因此规划层有价值；但“多个 Agent”“并行模块生成”“Review/BuildFix”这些命名夸大了实际能力，也容易诱导继续堆节点。

此外，图当前仍是顺序链；即使 Architect 写入 `parallelizable=true`，执行仍是单一 Agent 工具循环。复杂 DAG 检查点为确定性规则阶段带来的持久化成本，是否值得，需要用消融评测证明。

#### 建议设计

1. 将七个“AgentNode”按真实职责重命名并收敛为 3 个阶段：`PlanCompiler -> AgentRuntime -> Verifier/Repairer`。
2. Planner/Context/Architect/Code 合并为可测试的 `GenerationPlanCompiler`，输出强类型 `GenerationPlan`，不再用松散 `Map<String,Object>` 在节点间传递。
3. 真实 Review 必须发生在代码 diff 之后；BuildFix 必须消费真实构建诊断。前置规则只叫 `PlanValidator` 和 `RepairPolicyCompiler`。
4. 保留自主 Agent 的显式工具循环、ChangePlan、上下文压缩、审批恢复和完成判定，这些才是可借鉴 Claude Code / Codex 的核心。
5. 用消融实验比较 `无规划 / 精简规划 / 当前七节点` 的成功率、首次构建通过率、token、TTP 和总耗时；没有净收益的节点删除。

#### 用户价值

- 减少重复上下文组装、检查点 IO 和认知负担，缩短进入真正代码生成的时间。
- 类名、阶段名和实际能力一致，后续工程师更难在错误抽象上继续堆代码。
- 强类型计划减少运行时 cast、字符串 key 漏配和制品版本兼容问题。

#### 验收

- 进入首次真实模型代码调用前的准备耗时 P90 下降至少 30%。
- 精简方案的生成成功率和一次构建通过率不得下降，平均 token 不增加。
- 编排核心不再通过裸字符串读取关键 artifact；新增计划字段只改强类型模型及其消费者。

---

## 5. 隐藏 Bug 与代码异味清单

以下问题不建议拆成独立大项目，应随五项核心改造一起收口：

| 级别 | 问题 | 归属优化项 |
| --- | --- | --- |
| S1 | 工作区已发布后，后续 DB/积分/任务终态失败可导致发布结果与任务状态矛盾 | P0-1 |
| S1 | 成功路径存在两处积分结算调用，责任重复，依赖幂等掩盖设计问题 | P0-1 |
| S1 | 租约恢复终态化绕过 Trace、记忆和即时结算的统一业务收口 | P0-1 |
| S2 | `TASK_DONE` 可能早于持久任务终态，刷新后出现状态倒退 | P0-1 |
| S2 | 应用级历史低分可把无关的简单编辑升级到 Heavy，成本和等待被放大 | P0-2/P0-3 |
| S2 | 暂定预览在失败路径没有显式任务级停止，最长依赖 20 分钟 idle 回收 | P1-4 |
| S2 | execution workspace 的 `clear` 只清内存映射，不清失败目录；磁盘保留无 TTL 契约 | P1-4 |
| S3 | 线上关键词、IntentProfile、shadow engine 三套场景规则并存 | P0-2 |
| S3 | 七“Agent”多数为确定性规则节点，命名与执行能力不一致 | P1-5 |
| S3 | 关键编排制品大量使用字符串 key 与 `Map<String,Object>`，编译器无法保护契约 | P1-5 |

---

## 6. 两周实施顺序

### 第一周：先修一致性和可观测性

| 天 | 交付 | 当天可验证结果 |
| --- | --- | --- |
| 1-2 | 定义 `GenerationFinalizationCommand` 与唯一 Finalizer；先接管普通 Pipeline | 故障注入下不再出现任务/发布矛盾 |
| 3 | Heavy、取消、提交补偿、恢复扫描迁移到 Finalizer | 全部终态入口统一；删除重复积分结算 |
| 4 | terminal outbox + 资源清理事件 | SSE、记忆、临时资源可重试且幂等 |
| 5 | 路由 provenance 落库与快速路由标注集 | 每个任务能解释为何选中当前 route |

### 第二周：让路由和 Agent 改动可证明

| 天 | 交付 | 当天可验证结果 |
| --- | --- | --- |
| 6-7 | IntentProfile 单一决策器，以 shadow 运行 | 输出混淆矩阵和错误升级/降级样本 |
| 8 | 评测补 route assertion、HEAVY、恢复/取消场景 | 发布门禁能阻止路由回归 |
| 9 | 反馈 provenance 分析视图 | 低评分可定位 route/model/prompt/tool/repair |
| 10 | 规划层消融基准与精简 ADR | 用数据决定保留或合并哪些“Agent”节点 |

不要同时改五项实现。优先把 P0-1 完成并稳定，再推进路由和 Agent 结构；否则终态数据本身不可信，后续评测结论也会失真。

---

## 7. 最终目标指标

| 目标 | 核心指标 |
| --- | --- |
| 用户体验 | TTP P90、总耗时 P90、取消资源释放时延、状态不一致数 |
| 生成质量 | 任务成功率、一次构建通过率、运行时/视觉通过率、低评分率、返工轮次 |
| 生成效率 | 单成功任务 token、积分成本、工具调用数、fallback 率、repair rounds |
| 场景逻辑 | route accuracy、高风险错误降级率、简单任务错误升级率、shadow 晋级收益 |
| 工程质量 | 唯一终态入口数、强类型计划覆盖率、终态补偿成功率、临时资源泄漏数 |

建议将北极星指标定义为：

```text
单位成功交付成本
= 总模型与运行资源成本 / 通过完成证据且未被用户低分否定的任务数
```

它能同时约束质量、速度和成本，避免单独追求“更快”“token 更少”或“成功率更高”造成局部优化。

---

## 8. 不建议做的事

- 不引入第二套 Agent 框架或外部工作流引擎；先用现有设施收口所有权。
- 不按“Agent 数量”衡量先进性；以工具闭环、可恢复性和评测收益衡量。
- 不直接让小模型结果在线改 route；必须先有标注集、shadow 和晋级门禁。
- 不把所有失败工作区永久保留；诊断价值必须有 TTL、配额和清理责任。
- 不为了全局事务把文件系统硬塞进数据库事务；继续使用 journal + outbox + 幂等补偿。
- 不先做大规模包结构重排；上述五项稳定后，再按真实职责移动类。

---

## 9. 审计边界

本次未执行真实模型端到端生成，也未读取线上 Prometheus、Trace、用户反馈分布或成本数据。因此：

- 终态分段、重复结算责任、路由多事实源、资源清理缺口属于代码可确认问题。
- “准备耗时下降 30%”“路由准确率 90%”属于建议验收目标，不是已测得收益。
- 当前工作区的 Dev Server 所有权改动尚未提交，合并前应优先补失败/取消/超时资源收口测试。

### 本轮落地记录

- 第一轮已提交 `e4feb63`：统一任务终态收口。
- 第二轮在当前提交中将 `IntentProfileRoutingPolicy` 接入生产策略链，关键词策略仅作为兼容回退，影子路由与生产路由共享同一画像决策器。
- Benchmark 任务支持 `expectedRoute/forbiddenRoutes`，新增 `HEAVY_EXPERT` 架构迁移样本；报告聚合路由准确率、错误升级率、错误降级率，发布门禁可直接阻断路由回归。
