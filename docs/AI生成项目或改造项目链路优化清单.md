# AI 生成/改修项目链路综合评估与优化清单

> 评估日期：2026-07-28  
> 代码基线：`dev @ b982612`（含本轮测试契约修复）  
> 北极星目标：**在可接受质量下，尽可能缩短“可预览时间（TTP）”与“可交付时间（TTD）”**

---

## 0. 一句话结论

当前后端已经具备生产级任务运行时雏形（异步任务、幂等、租约 fencing、SSE 续传、审批恢复、工作区发布、积分结算、trace/benchmark），但**复杂度增长快于核心生成收益**。  
下一阶段应从“继续增加治理模块”转向：

1. **深模块化关键路径**  
2. **速度与质量闭环**  
3. **对标 Claude Code 的 harness 思路，而不是照搬其交互形态**

---

## 1. 当前成熟度判断

### 1.1 总体评级

| 维度 | 评级 | 说明 |
| --- | --- | --- |
| 任务运行时可靠性 | B+ | 租约、恢复、取消、审批挂起已成型 |
| 生成链路可观测性 | B+ | Trace、Span、Latency Ledger、Benchmark 有基础 |
| 路由与意图理解 | C+ | 仍以关键词/长度启发为主，误判风险高 |
| Agent 执行效率 | C | DAG 与工具调用均为顺序执行，完成条件偏弱 |
| 质量验证闭环 | B- | 有构建/修复/预览，但验证图未按风险分层统一 |
| 用户体验叙事 | C+ | 事件偏内部实现细节，缺少用户可理解的阶段进度 |
| AI 工程生产化 | B | 有容量治理、failover、hedge、积分预授权，但链路耦合仍重 |

### 1.2 总体判断

> 基础设施方向正确，但尚未形成“意图 → 执行计划 → 受控 Agent 尝试 → 工作区事务 → 验证 → 可回滚交付”的深模块主轴。  
> 现在最该做的不是再堆一层 Interface，而是把关键路径做成可独立演进、可独立测试、可独立灰度的深模块。

---

## 2. 真实链路（以代码为准）

```text
GenerationTaskController.submit
  → AppServiceImpl.submitGeneration
     1) resolve 幂等键
     2) 读取应用并做权限校验
     3) enableDatabaseForGenerationIfNeeded  ← 当前在幂等短路前
     4) GenerationTaskOrchestrator.start
  → GenerationModeRouter.route
  → GenerationTaskSubmissionService.submit
  → 任务持久化 / 准入 / Dispatcher
  → GenerationTaskCommandExecutionService
  → GenerationPipelineExecutor
       ├─ SlotFillGenerationPipeline
       ├─ LightweightEditGenerationPipeline
       ├─ AgentEditGenerationPipeline
       └─ HeavyGenerationPipeline
            → HeavyGenerationCoordinator
            → AgentGenerationOrchestrator（DAG 节点）
            → DefaultGenerationAgentRuntime（真正的模型+工具循环）
            → 构建 / 修复 / 提交 / 发布 / 预览
```

### 2.1 四类执行模式

| 模式 | 适用场景 | 当前优势 | 当前风险 |
| --- | --- | --- | --- |
| Slot Fill | 结构化槽位填充 | 快、确定性强 | 覆盖面有限 |
| Lightweight Edit | 文案/样式/小改 | 延迟低 | 关键词误判会把复杂改修降级 |
| Agent Edit | 中等复杂度改修 | 比 Heavy 更轻 | 完成条件与验证策略不统一 |
| Heavy Expert | 新建/大改 | 能力最全 | 路径最长、Coordinator 职责过宽 |

### 2.2 “多 Agent” 的真实含义

当前代码里的 Planner / Template / Context / Architect / Code / Review / BuildFix，**多数是确定性 Workflow Node**，并不都是独立 LLM Agent。

真正具备模型工具循环的是：

- `DefaultGenerationAgentRuntime`

因此必须区分五层，避免“为了多 Agent 而多 Agent”：

1. **确定性 Workflow Node**（规则、装配、校验）
2. **主 LLM Agent Runtime**（不确定编码决策）
3. **独立 Evaluator / Reviewer Agent**（质量门禁，不共享污染上下文）
4. **Repair Agent**（构建/运行时失败后的定向修复）
5. **后台只读 Subagent**（高输出调查，不抢主线程）

推荐原则：

> 规则工作流负责确定性；主 Agent 负责不确定性；Evaluator 负责质量；Subagent 负责独立高输出调查；任务运行时负责可靠性；工作区事务负责可回滚交付。

---

## 3. 已确认的优点（应保留）

1. **异步任务化**：提交立即返回 `taskId`，不阻塞事件流。
2. **生产治理底座**：幂等、准入、租约、fencing、恢复、取消。
3. **人机协同能力**：破坏性工具审批、挂起恢复。
4. **工作区隔离与发布**：快照、发布、预览里程碑意识存在。
5. **模型治理**：failover、首 Token hedge、容量与 trace。
6. **商业闭环雏形**：积分预授权 / 结算幂等（`chargeGenerationTask` 已有 settled/流水恢复逻辑）。
7. **可度量基础**：Benchmark、Latency Ledger、路由遥测。

这些能力说明项目已经不是“Demo 级编排”，而是在往生产 AI 应用走。问题在于**关键生成收益路径仍被顺序执行、弱完成条件、浅路由和过宽 Coordinator 拖住**。

---

## 4. 已确认问题与风险

### 4.1 P0 级问题

#### A. 幂等重放前存在业务副作用

`AppServiceImpl.submitGeneration` 在真正准入/复用旧任务前，就可能执行：

```text
enableDatabaseForGenerationIfNeeded(app, message)
```

而幂等命中发生在后续 `GenerationTaskAdmissionService.admit(...)`。

后果：

- 重放请求可能先更新数据库资源/时间戳；
- 资源启用失败时，可能无法干净返回原任务；
- 违反“幂等重放无新增业务副作用”。

**修复方向：**

1. 幂等查询必须在任何生成相关副作用前短路；或
2. 提交阶段只持久化“需要数据库”意图，由 worker 在 fencing 保护下启用资源。

#### B. API 时间字段契约曾不稳定

`GenerationTaskSubmissionVO.submittedAt/deadlineAt` 在 standalone MockMvc 下可能被序列化为数值时间戳。

本轮已修复：

- 给 VO 显式加 `@JsonFormat(shape = STRING)`，锁定 ISO-8601 契约。

#### C. 中文规范未完全落地

路由层仍存在英文异常：

- `generation task request is invalid`
- `application code generation type is invalid`
- `generation workspace is invalid`

以及大量英文 routing reason。  
对用户提示、异常、日志摘要应统一中文门禁。

### 4.2 速度瓶颈（已代码确认）

#### A. DAG 不是并行执行器

`GenerationDagRunner` 注释与实现均表明：

> 按声明顺序执行就绪节点

即使多个节点同时 ready，也同步顺序执行。  
当前 DAG 的价值是：

- 依赖表达
- 检查点
- 恢复

**不是**提速。

#### B. Agent 工具请求逐个串行

`DefaultGenerationAgentRuntime` 对同一轮 `toolExecutionRequests` 逐个执行。  
即使全是只读搜索/读文件，也无法并行。

#### C. 路由误判导致“该轻不轻、该重不重”

`looksLikeSmallSingleFileEdit()` 仅看：

- 长度 ≤ 160
- 包含“修改/调整/更改/替换/改成/换成”

典型误判：

- “把登录页改成完整电商后台，并增加订单、权限、支付”
- “颜色改成红色，同时重写登录鉴权和数据库”
- “把首页改成多租户管理平台”

轻路径误入会损质量；重路径误入会损速度。

### 4.3 质量风险

#### A. Agent 完成条件偏弱

当前核心条件近似：

```text
模型本轮不再调用工具 → completeGeneration
```

虽然有 `GenerationAgentProductivityGuard` 抑制空转，但**第一轮直接无工具返回**仍可能被当成完成。

缺少统一的“有效交付证据”：

- 编辑任务：有效 diff / 明确“无需修改”证据
- 创建任务：入口文件、关键产物、可运行证据
- 验证任务：构建/预览/契约结果

#### B. HeavyGenerationCoordinator 职责过宽

约 831 行、十余个依赖，同时拥有：

- 执行上下文
- 性能任务
- 准备
- Session
- 工作区绑定
- Agent 执行
- 审批挂起
- 构建修复
- 终态
- 事件
- 生命周期
- 工具上下文清理

这不是“再抽几个 interface”能解决的，需要**深模块所有权重构**。

#### C. 用户体验事件过“工程化”

内部节点 running/done、工具细节、恢复细节直接暴露，用户感知会是“系统在忙”，而不是：

1. 已理解你的需求  
2. 正在改哪些范围  
3. 已可预览  
4. 正在做质量校验  
5. 已交付 / 需要你确认

---

## 5. 对标 Claude Code：借鉴什么，不照搬什么

### 5.1 应借鉴

| Claude Code / Anthropic 思路 | 本项目落点 |
| --- | --- |
| Subagents 独立上下文、专用工具与权限 | 只读调查 Subagent、独立 Evaluator，不污染主会话 |
| Hooks 在生命周期强制执行确定性检查 | 格式化、安全、构建、契约校验走 Hook/流水线，不靠 Prompt |
| Memory 分层（项目规则 vs 任务临时上下文） | 项目级约束持久化；任务上下文可压缩、可恢复 |
| Checkpointing | 工作区事务 + 任务检查点，支持回退，但不替代 Git |
| Skills 渐进披露 | 技能/模板/领域知识按需加载，不塞满 system prompt |
| Context Engineering | 高信号状态优先；compaction；结构化进度账本 |
| Tool Design | 工具返回高信息密度、可操作错误、结果裁剪与缓存 |
| Long-running harness | 每轮明确：完成了什么、剩余什么、当前证据是什么 |

### 5.2 不应照搬

1. **不要把全部 DAG 节点都模型化**，否则延迟、成本和故障面一起上升。  
2. **不要为了“多 Agent 叙事”制造浅模块**。  
3. **不要把 CLI 交互体验原样搬到 SaaS 产品**；本产品要的是“自然语言 → 可预览网站”的业务闭环。  
4. **不要用更多 Prompt 代替工程约束**。质量门禁必须确定性强制。

---

## 6. 目标架构（深模块，而不是名词堆砌）

```text
自然语言
  ↓
Intent Understanding Module
  → IntentProfile
  ↓
Execution Planning Module
  → GenerationExecutionPlan（不可变）
  ↓
Task Runtime（幂等/租约/恢复/取消/终态唯一所有权）
  ↓
Generation Attempt Runtime（模型轮次/工具/审批/压缩/预算/完成条件）
  ⇄ Workspace Transaction（快照/Patch/验证/Commit/Rollback/Publish）
  ⇄ Verification Module（静态/构建/运行时/浏览器/契约/非回归）
  ↓
Experience Event Module（用户可理解进度）
  ↓
Evaluation & Learning（Benchmark / Champion-Challenger / 反馈闭环）
```

### 6.1 各模块职责边界

#### 1) Intent Understanding

输入：自然语言、工作区画像、历史反馈  
输出：`IntentProfile`

建议字段：

- `operationType`：create / edit / repair / explain
- `affectedScopes`
- `semanticComplexity`
- `requiresBackend`
- `requiresDatabase`
- `destructiveRisk`
- `expectedFileCount`
- `validationRisk`
- `confidence`

#### 2) Execution Planning

输出不可变 `GenerationExecutionPlan`：

- route
- modelProfile
- contextBudget
- toolPolicy
- validationGraph
- repairBudget
- commitPolicy
- previewPolicy
- SLA

**关键点：** 路由、模型、验证、修复预算必须一次规划，避免执行中临时拍脑袋。

#### 3) Generation Attempt Runtime

- 模型轮次与工具循环
- 只读工具可并行
- 写工具/审批/副作用串行
- 上下文 compaction
- 完成条件 = 意图证据 + 工作区证据 + 验证门槛

#### 4) Workspace Transaction

- 隔离工作区
- 快照
- Patch 应用
- optimistic concurrency
- 验证通过后 Commit
- 失败 Rollback
- 成功 Publish / Preview

#### 5) Verification Module

按风险选择验证图，而不是永远 Full Expert：

- FAST：静态 + 关键文件语法
- STANDARD：构建 + 基础预览
- EXPERT：构建 + 运行时 + 浏览器/契约/非回归

#### 6) Task Runtime

唯一拥有：

- 幂等
- 租约
- fencing
- 恢复
- 取消
- 终态
- 积分结算触发点

#### 7) Experience Event Module

面向用户的阶段事件，不直接暴露内部 Agent 噪声。

#### 8) Evaluation & Learning

- 离线 Benchmark
- 在线 shadow 路由
- Champion / Challenger
- 用户一次验收率、再次修改率反馈

---

## 7. 速度 × 质量并进的关键设计

### 7.1 速度：只并行“无副作用准备”

允许并行：

- 模板元数据读取
- 工作记忆读取
- 语义索引扫描
- 依赖元数据
- 只读项目搜索

必须串行：

- 写工作区
- 评审结论落地
- 构建
- Commit
- Publish

### 7.2 质量：完成条件升级

禁止把“模型不再调用工具”当作唯一完成信号。

统一完成判定：

1. 意图覆盖证据  
2. 工作区有效变更或“无需修改”证明  
3. 验证图达到计划要求  
4. 无未处理审批  
5. 无未回收的取消/租约冲突

### 7.3 路由：高置信本地，低置信小模型，禁止盲目 Heavy

策略顺序建议：

1. 高置信本地规则直接命中  
2. 低置信调用小模型结构化分类  
3. 决策支持 reject / escalate  
4. 低置信优先升到 Agent Edit，不直接 Heavy  
5. shadow mode 对比新旧路由  
6. 历史遥测只做校准，不替代任务语义

---

## 8. 实施优先级（按依赖排序）

### P0：1～3 天，发布门禁（无前置依赖）

目标：先让主链路“正确、可回归、可发布”。

| 项 | 依赖 | 验收标准 |
| --- | --- | --- |
| 全量测试保持绿灯 | 无 | `mvn test` 0 failure / 0 error |
| API 时间字段 ISO 契约 | 无 | Controller 契约测试固定 ISO-8601 |
| 幂等副作用前移修复 | 无 | 重放请求不新增 DB/资源副作用 |
| 中文异常/提示门禁 | 无 | 用户可见文案无英文硬编码 |
| 最小 E2E 冒烟 | 无 | 创建/小编/复杂编/取消/审批/幂等/SSE 续传 |
| 仓库污染治理 | 无 | `.tmp`、临时产物不入库 |

**本轮已完成：**

- 测试契约漂移修复（Benchmark / Admission）
- `GenerationTaskSubmissionVO` 时间序列化契约固定
- Heavy 初始化测试补齐 `startedAt/deadlineAt`
- 全量测试：`2401` 通过，`0` 失败，`40` 跳过

**P0 发布门禁补齐（2026-07-31）：**

- 新增统一用户可见中文文案解析器，HTTP 与 SSE 异常出口对纯英文内部诊断统一回退为稳定中文；Controller 异常字面量和 `ErrorCode` 增加架构门禁
- 校验框架的英文默认原因统一转换为中文，保留参数名以便客户端定位错误字段
- 新增 `generation-release-smoke` Maven Profile，固定覆盖创建、轻量编辑、复杂编辑、取消、审批、幂等、SSE 续传七类进程内发布冒烟场景
- 发布冒烟：`8` 项通过（`7` 个场景 + `1` 个完整性契约），`0` 失败，`0` 错误
- 全量回归：`2467` 项执行，`0` 失败，`0` 错误，`40` 跳过

### P1：1～2 周，建立可度量闭环（依赖 P0）

| 项 | 依赖 | 说明 |
| --- | --- | --- |
| 基线指标盘 | P0 | TTFA/TTFT/TTP/TTD 等 |
| `IntentProfile` | P0 | 结构化意图 |
| 新路由 shadow mode | IntentProfile | 不直接切主流量 |
| 统一 `GenerationExecutionPlan` | IntentProfile | 一次规划执行约束 |
| 统一验证策略 | ExecutionPlan | 按风险选验证图 |
| 工作区事务语义显式化 | P0 | 快照/提交/回滚所有权清晰 |
| 用户进度事件重构（已完成） | ExecutionPlan | 面向体验，不面向内部节点 |
| 完成条件加入有效证据（已完成） | Attempt Runtime | 防空完成 |

**第二轮已完成：用户进度事件重构（2026-08-03）**

- 新增 `orchestration/experience` 独立模块，由 `GenerationExperienceEventMapper` 统一将领域事件、运行时 `agent_event` 映射为面向用户的稳定进度。
- 公共 `generation_stage` v1 契约只暴露 `audience`、`contractVersion`、`stage`、`message`、`phase` 和 `terminal`，稳定阶段限定为：`understanding`、`planning`、`implementing`、`preview_ready`、`verifying`、`awaiting_approval`、`delivered`。
- 内部 Agent、节点、路由、预算和恢复信息仍保留在日志、trace 和指标中，不再作为用户主进度，未知内部阶段安全回退为 `implementing`。
- `GenerationSseEventMapper` 在普通 SSE 和持久化 sequenced SSE 上统一执行“体验映射 → 公共脱敏 → 订阅级阶段去重”；去重不使用全局状态，且保留 SSE ID 跳号语义供断线续传对齐。
- 审批事件为唯一兼容例外：保留 `agent_event` 以支持前端审批卡，但只公开 `taskId`、`action`、`approvalId` 等最小决策字段，并使用中文公共标签。
- 前端通过 `resolveUserProgressEvent` 消费稳定阶段，不再展示 `${agentName}处理中`；只有 `ai_delta` 进入回答正文，阶段、Agent 和工具状态不再混入最终回答。
- 验证已通过：后端全量 `2476` 项执行，`0` 失败，`0` 错误，`40` 跳过；`generation-release-smoke` 发布冒烟 `8` 项全部通过；前端 Vitest `41` 项全部通过，TypeScript 类型检查和 Vite 生产构建通过。

**第三轮已完成：完成条件加入有效证据（2026-08-04）**

- 新增 `orchestration/attempt/completion` 独立完成门禁模块，以结构化证据集统一判断任务是否允许进入 `SUCCESS`，不再把“模型停止调用工具”直接等同于完成。
- 完成证据覆盖意图、工作区结果和冻结验证图：必须具备意图覆盖证据、有效工作区变更或结构化 `no_change_justification`，并按 FAST / BUILD / EXPERT 要求提供对应验证通过证据。
- 同步流水线、Heavy 最终化和 Agent Runtime 三条成功路径统一采用 fail-closed；证据不足时不会发布工作区、提交成功终态、执行成功计费或发送 `TASK_DONE`。
- 最终判定会重新检查取消/超时状态、执行上下文可继续性以及持久任务栅栏与租约归属，避免旧执行纪元、等待审批或已失去所有权的任务误报成功。
- Heavy 验证结果通过 `verification_evidence` 结构化制品沉淀并支持恢复合并；普通模型说明文字不视为“无需修改”证明。
- 公共用户进度契约保持稳定：完成门禁内部证据、验证步骤和租约细节不向用户泄漏，校验与交付仍只通过 `verifying`、`delivered` 等公共阶段表达。
- 最终验证已通过：后端全量 `2494` 项执行，`0` 失败，`0` 错误，`40` 跳过；`generation-release-smoke` 发布冒烟 `8` 项全部通过；前端 Vitest `41` 项全部通过，TypeScript 类型检查和 Vite 生产构建通过。

### P2：2～4 周，提速与质量并进（依赖 ExecutionPlan / Verification / WorkspaceTransaction）

| 项 | 依赖 | 收益 |
| --- | --- | --- |
| Token 预算上下文 + compaction | Attempt Runtime | 降成本、稳长任务 |
| 只读工具并行 | 工具能力元数据 | 降首轮探索延迟 |
| 独立 Reviewer/Evaluator | 独立上下文 | 提质量，不污染主 Agent |
| 浏览器/视觉/契约验证 | Verification | 降“构建过了但体验坏” |
| 工具结果结构化缓存去重 | Tool Layer | 降重复调用 |
| 后台只读 Subagent | 高输出调查场景 | 不阻塞主路径 |

### P3：持续优化（依赖 Benchmark 与灰度体系）

- Benchmark 数据集持续扩充
- Champion / Challenger
- Prompt / 模型 / 路由联合灰度
- 用户反馈闭环
- 成本-质量-延迟 Pareto 优化

---

## 9. 指标与验收标准

### 9.1 北极星

> **可接受质量下的可预览时间（TTP @ Quality Gate）**

### 9.2 必采指标

| 指标 | 含义 |
| --- | --- |
| TTFA | 提交 → 首个有意义事件 |
| TTFT | 提交 → 首模型 Token |
| TTP | 提交 → 可预览 |
| TTD | 提交 → 最终交付 |
| 首次构建通过率 | 不经修复直接过构建 |
| 自动修复成功率 | 修复后通过验证 |
| 路由误判率 | 轻/重路径判错 |
| fallback 率 | 降级/切换频率 |
| 修改非回归率 | 改修不破坏原功能 |
| 一次验收通过率 | 用户不再追加修改 |
| 再次修改率 | 交付后返工 |
| 每成功任务 Token/成本 | 成本效率 |
| 重复工具调用率 | 探索浪费 |
| 上下文命中率 | 检索/记忆有效性 |
| 取消生效延迟 | 用户停止是否真停 |
| 恢复成功率 | 断线/重启/审批后恢复 |

### 9.3 分模式 SLA 建议（首期基线，随后用真实数据校准）

| 模式 | TTFA | TTP | 质量门槛 |
| --- | --- | --- | --- |
| Lightweight Edit | < 1s | < 15s | FAST 验证通过 |
| Agent Edit | < 1.5s | < 60s | STANDARD 验证通过 |
| Heavy Create | < 2s | < 180s | EXPERT 验证通过，可预览 |

---

## 10. 建议的代码结构演进（避免技术债）

### 10.1 推荐包边界

```text
orchestration/
  intent/          # IntentProfile 解析与校准
  plan/            # GenerationExecutionPlan
  attempt/         # Agent Runtime / 完成条件 / 压缩
  workspace/       # 事务、快照、发布（已有，需收口）
  verification/    # 验证图与修复策略
  experience/      # 用户进度事件映射
  runtime/task/    # 任务运行时（已有，保持唯一终态所有权）
  learning/        # benchmark / shadow / challenger
```

### 10.2 设计约束

1. **开闭原则**：新增路由/验证/工具策略靠扩展，不改主协调器大 switch。  
2. **单一所有权**：终态、积分结算、工作区发布必须有且仅有一个写入所有者。  
3. **深模块优先**：对外小接口，对内高内聚；禁止“每个名词一个浅 Service”。  
4. **中文友好**：注释、异常、用户提示统一中文。  
5. **不留临时债**：不做“先 hardcode 后面再清”的捷径。

### 10.3 近期待拆深模块

1. `GenerationAttemptRuntime`  
2. `WorkspaceTransaction`  
3. `VerificationRuntime`  
4. `TaskCompletionCoordinator`  
5. `IntentProfileService`  
6. `GenerationExecutionPlanner`

---

## 11. 关键路径核验结论（本轮）

| 主题 | 结论 |
| --- | --- |
| 取消 | 控制面可 `requestCancellation`；执行面多处 `shouldStop` 已接入进程/依赖/校验 |
| SSE 续传 | 支持 `afterSequence` + `Last-Event-ID` |
| 积分扣费 | `chargeGenerationTask` 具备 settled/流水恢复，具备幂等结算基础 |
| DAG | 可恢复工作流图，非并行加速器 |
| 工具循环 | 同轮工具串行；只读并行是明确提速点 |
| 路由 | 关键词启发为主，存在系统性误判面 |
| 完成条件 | 偏“无工具请求即完成”，需证据化 |
| 幂等 | 仍有“准入前副作用”缺口，属 P0 |

---

## 12. 本轮已落地修改

1. `OrchestratedGenerationBenchmarkExecutorTest`  
   - 对齐 `GenerationTaskSubmissionReceipt` 新契约
2. `GenerationTaskAdmissionServiceTest`  
   - 补齐 `GenerationTaskStatus` import
3. `GenerationTaskSubmissionVO`  
   - `submittedAt` / `deadlineAt` 显式 ISO 字符串序列化
4. `HeavyGenerationCoordinatorInitializationTest`  
   - stub `executionContext.startedAt/deadlineAt`，消除 NPE

### 测试结果

```text
定向测试：GenerationTaskControllerTest + HeavyGenerationCoordinatorInitializationTest
→ 13 tests, 0 failure

全量测试：mvn test
→ Tests run: 2401, Failures: 0, Errors: 0, Skipped: 40
→ BUILD SUCCESS
```

---

## 13. 立刻可执行的下一步（建议按此顺序开工）

1. **修幂等副作用顺序**（真正的生产正确性）  
2. **补中文门禁与路由 reason 中文化**  
3. **引入 IntentProfile + shadow 路由**（先观测，再切流）  
4. **完成条件证据化**（防空完成）  
5. **只读工具并行**（低风险提速）  
6. **拆 HeavyCoordinator 为深模块**（降低后续改动成本）

---

## 14. 最终建议

不要继续沿“再加治理开关/再加一层接口”的路径扩张。  
要用 Claude Code 的 harness 思想，把本项目做成：

> **一个面向“自然语言建站/改站”的生产级 Generation Operating System**

它的竞争力不在于 Agent 名词多少，而在于：

- 路由是否判得准  
- 执行是否够快  
- 验证是否挡得住质量回退  
- 失败是否可恢复  
- 用户是否更早看到可预览结果  
- 每次成功交付的成本是否可控

**质量和速度必须同一套执行计划驱动，而不是两套事后补丁。**

---
---

# 第二部分：上下文工程 / 思考模式 / 人在回路 / 图编排 / 稳定性

> 补充日期：2026-07-29  
> 代码基线：`dev @ b982612`  
> 本部分是第一部分的落地补充，聚焦五项能力的**代码级实施**，供 agent 直接开发。  
> 每项均标注「已有 / 缺口 / 落地动作 / 验收」，不重复第一部分已述内容。

## 15. 五项能力的真实现状核验

以下结论全部来自代码，不是设计意图。

| 能力 | 已有实现 | 关键缺口 |
| --- | --- | --- |
| 长期记忆 | `MilvusLongTermMemoryStore` + `FailoverLongTermMemoryStore` + 双 outbox + 启动契约校验 | 只写入「结果」，无**经验蒸馏**；召回未按意图分型 |
| 短期记忆 | `GenerationWorkingMemoryService`（有界、2h、按任务） | 与长期记忆无晋升通道；跨任务不复用 |
| 上下文装配 | `AiContextPackAssembler` + `AiContextRelevanceScorer` + tokenizer 预算 + 时间衰减 | 无**语义缓存**；无 KV-cache 前缀稳定化 |
| 上下文压缩 | `CompletedToolCallContextCompactor`（仅裁剪已完成写文件参数） | 无**会话级摘要压缩**，长任务仍会顶到窗口 |
| 思考模式 | `GenerationThinkingMode{FAST,STANDARD,DEEP}` + `OpenAiThinkingPolicy` | **未与 ExecutionPlan 联动**，等于常量 |
| 人在回路 | `ToolApprovalService` + 挂起恢复 + TTL 过期协调 | 只管**破坏性工具**；无澄清回路、无计划确认门 |
| 图编排 | `GenerationDagRunner` 依赖表达 + 指纹 + 检查点 + 恢复 | **顺序执行**（`run()` 内 `for (readyNodes)`）；无条件边、无循环边 |
| 稳定性 | 租约 fencing、幂等、failover、hedge、熔断、准入 | 见第一部分 P0：幂等前副作用 |

### 15.1 一句话判断

> 底座件件齐全，但**没有一条贯通的上下文生命周期**：短期记忆不晋升、长期记忆不蒸馏、思考模式不联动、澄清不存在、图不并行。  
> 这五件事互相咬合，应按 §21 的依赖顺序做，不能挑着做。

---

## 16. 上下文工程：四层记忆与生命周期

### 16.1 目标分层

当前只有「工作记忆 / 长期语义记忆」两层，中间断裂。补成四层：

```text
L1 会话记忆  ChatMemory（Redis, TTL 1h）
   └ 当前对话轮次，原样保留最近 N 轮

L2 工作记忆  GenerationWorkingMemory（进程内, 2h）
   └ 当前任务的步骤、事件、工具结果、失败原因

L3 情景记忆  EpisodicMemory（MySQL, 90d）   ← 新增
   └ 单次任务的「结构化复盘」：意图、计划、改了什么、成败、耗时、返工

L4 语义记忆  SemanticMemory（Milvus, 永久）
   └ 从 L3 蒸馏出的「可复用经验」：项目约定、踩过的坑、有效方案
```

**关键原则：L4 只接受蒸馏产物，不接受原始日志。**  
当前 `GenerationOutcomeMemoryDocument` 直接把结果写 Milvus，会让向量库堆满低信号噪声，召回质量随时间衰减。

### 16.2 新增：情景记忆（L3）

> **设计修正（2026-07-29，代码核实后）**  
> 初版设计建议新建 `generation_episode` 表。核对 `GenerationTask` 实体后**放弃该方案**。

`generation_task` 已具备 L3 所需的大部分字段：

| L3 需要 | generation_task 现状 |
| --- | --- |
| 归属与幂等 | `appId` / `userId` / `tenantId` / `taskId` / `idempotencyKeyHash` |
| 路由 | `route` / `orchestrationMode` / `originalCodeGenType` / `targetCodeGenType` |
| 结果 | `status` / `errorMessage` / `qualityGate` |
| 耗时 | `startTime` / `endTime` / `durationMs` |
| 成本 | `totalTokens` / `creditCost` / `creditCharged` |
| 记忆摘要 | `memorySummary` |
| **蒸馏所需的 outbox 租约** | `memoryIndexedAt` / `memoryIndexAttempts` / `memoryIndexError` / `memoryIndexNextAttemptAt` / `memoryIndexLeaseOwner` / `memoryIndexLeaseUntil` |

新建独立表会带来三个问题：

1. **重复 9 个字段**，两处写入产生一致性风险。
2. **需要复制一套 outbox 租约机制** —— 直接违反 §16.3 自己定的约束「不要新写一套 outbox」。
3. 蒸馏时需 join，而现有 `GenerationMemoryOutboxService` 的 claim 逻辑是单表扫描。

**修正方案：扩展 `generation_task`，只补真正缺失的 outcome 质量字段。**

| 新增字段 | 类型 | 说明 |
| --- | --- | --- |
| `thinkingMode` | varchar(16) | 实际使用的思考档位（§17.2 落地后写入） |
| `changedFileCount` | int | 有效变更文件数 |
| `firstBuildPassed` | tinyint | 是否免修复通过构建 |
| `repairRounds` | int | 实际修复轮次 |
| `firstPreviewMillis` | bigint | 提交 → 可预览（TTP） |
| `failureCategory` | varchar(64) | 复用 `GenerationErrorClassifier` 分类 |
| `reworkedAt` | datetime(6) | 交付后被追加改修的时间（§18.4 隐式信号） |
| `distilledAt` | datetime(6) | 已蒸馏时间，NULL 且满足条件则待蒸馏 |

其余 L3 语义字段全部复用现有列，**不重复建模**：
- `outcome` → 复用 `status`
- `ttdMillis` → 复用 `durationMs`
- `intentDigest` → 复用 `userPrompt`（已有 untrusted evidence 边界保护），蒸馏时才归一化，不额外落库

写入时机：`GenerationTaskLifecycleService.completeGeneration*`（现有终态唯一写入点，第 227–295 行），与 `userCreditService.chargeGenerationTask` 同一 `@Transactional` 边界，避免「扣了费没有复盘」。

**代价与权衡**：`generation_task` 已有 40+ 列，再加 8 列会让这张事务热表更宽。接受该代价，因为：一致性风险与 outbox 重复的成本更高；且该表已承载 `memoryIndex*` 与 `publication*` 等同类生命周期字段，扩展符合既有建模惯例。

**不要**把原始文件内容写入该表 —— 沿用现有 untrusted evidence 边界策略。

#### 实施状态（2026-07-29）

**已完成：字段与写入通道**

| 交付物 | 位置 |
| --- | --- |
| Flyway 迁移 | `sql/migrations/V20260729_1__generation_episodic_outcome_quality.sql` |
| 初始化脚本同步 | `sql/create_table.sql`（列、`idx_generation_task_distill_claim`、4 个 CHECK 约束） |
| 迁移说明 | `sql/migrations/README.md` |
| 实体字段 | `GenerationTask`（8 个字段） |
| 值对象 | `service/trace/GenerationOutcomeQuality`（可空语义、归一化、边界校验） |
| 写入通道 | `GenerationTraceMapper.completeRunningTask` 用 `COALESCE` 折叠进终态 UPDATE |
| 服务接口 | `GenerationTraceService.completeTask(.., outcomeQuality)` 与 `GenerationTaskLifecycleService.completeGeneration*(.., outcomeQuality)` 重载 |
| 测试 | `GenerationOutcomeQualityTest`（8 项边界）、`DefaultGenerationTraceServiceTest` 新增 2 项契约 |

全量测试 `2411` 通过、`0` 失败（基线 `2401`，新增 10 项）。

关键实现决定：
- `COALESCE(#{field}, field)` —— 传 `null` 表示不覆盖，重试不会擦掉已采集值。
- **旧 3 参数 `completeTask` / `completeGeneration*` 保持直接调用原协作路径**，不经由新重载委托。曾一度改成委托链，导致 `GenerationTaskLifecycleServiceTest` 的协作顺序断言失败 —— 那是真实回归而非测试过时，已改回实现。新增能力不得改变既有调用契约。

**未完成：指标采集**

调用方目前全部传 `null`，字段写入 NULL（语义为「未采集」）。需在各 pipeline 终态装配：

| 字段 | 数据来源 | 阻塞依赖 |
| --- | --- | --- |
| `changedFileCount` | `GenerationDiffSummaryService` | 无 |
| `repairRounds` | 修复循环计数器 | 无 |
| `failureCategory` | `GenerationErrorClassifier.classify()` | 无 |
| `firstPreviewMillis` | `FIRST_PREVIEW_READY` 事件时间戳 − `submittedAt` | 无 |
| `firstBuildPassed` | 首次构建结果且 `repairRounds == 0` | 无 |
| `thinkingMode` | `GenerationExecutionPlan` | **依赖 §17.2** |

前五项无阻塞依赖，可立即接入；`thinkingMode` 需等思考模式联动落地。

### 16.3 新增：经验蒸馏（L3 → L4）

新建 `orchestration/memory/distill/MemoryDistillationService`。

触发：后台任务，扫描 `generation_task` 中 `distilledAt IS NULL` 且同 `appId` 累积 ≥ N 条终态记录（建议 N=5）。因 L3 已合并进 `generation_task`（见 §16.2 修正），claim 逻辑可直接复用现有 `GenerationMemoryOutboxService` 的单表租约扫描，无需 join。

蒸馏逻辑（**用小模型，不用生成主模型**）：

```text
输入：同一 appId 的 5～20 条 EpisodicRecord
输出：0～3 条 SemanticMemory，每条形如
  {
    type: ARCHITECTURE_DECISION | FAILURE_LESSON | USER_PREFERENCE,
    content: "该项目 Vue 组件统一放 src/components/business/，
              曾因放错目录导致 2 次构建失败",
    confidence: 0.0~1.0,
    supportingEpisodes: [taskId...]
  }
```

**复用现有 `MemoryType` 枚举，不要新增类型。** 现有枚举已含 `TASK_OUTCOME` / `USER_FEEDBACK` / `USER_PREFERENCE` / `FAILURE_LESSON` / `ARCHITECTURE_DECISION`，语义完全够用：

| 蒸馏产物 | 复用类型 |
| --- | --- |
| 项目约定（目录结构、命名、技术选型） | `ARCHITECTURE_DECISION` |
| 踩坑教训（什么导致失败） | `FAILURE_LESSON` |
| 用户偏好（风格、交互习惯） | `USER_PREFERENCE` |

原始未蒸馏结果继续用 `TASK_OUTCOME`（现有 `GenerationOutcomeMemoryDocument` 行为不变），蒸馏产物用上面三类 —— 这样召回时可通过类型区分「原始噪声」与「已提炼经验」，无需改 schema。

落地约束：
1. 蒸馏产物必须带 `supportingEpisodes`，写入 Milvus `metadata` 字段（现有 schema 已有该字段，无需迁移），保证可追溯、可撤回。
2. `confidence < 0.5` 不入库，避免污染召回。
3. 蒸馏失败不阻塞主链路，`distilledAt` 保持 NULL 由下轮重试。
4. 复用现有 `GenerationMemoryOutboxService` 的租约与退避机制，**不要新写一套 outbox**。

### 16.4 召回：按意图分型，而非全局 top-k

当前 `SemanticMemoryQuery` 是单一 top-k + 最低分。改为**分型配额**：

| 意图 | ARCHITECTURE_DECISION | FAILURE_LESSON | USER_PREFERENCE | TASK_OUTCOME |
| --- | --- | --- | --- | --- |
| CREATE | 2 | 1 | 2 | 1 |
| LIGHT_EDIT | 1 | 1 | 1 | 0 |
| AGENT_EDIT | 2 | 2 | 1 | 1 |
| 修复场景 | 1 | 4 | 0 | 1 |

配额总数不超过现有 `AI_CONTEXT_PACK_MAX_SEMANTIC_MEMORY_SECTIONS`（默认 6），避免挤占其他 section 预算。

理由：创建时最需要「项目怎么组织 + 用户偏好什么风格」，修复时最需要「这个坑踩过」。当前单一 top-k 会让修复场景召回一堆无关的成功经验。

实现落点：`AiContextPackAssembler` 已有 section 预算机制与 `AiContextRelevanceScorer` 时间衰减，扩展为按 `MemoryType` 分配配额即可，**不要新建装配器**。`SemanticMemoryQuery` 需支持按类型过滤（Milvus filter 已支持 `memory_type` 字段）。

### 16.5 新增：语义缓存

新建 `orchestration/context/SemanticContextCache`。

用途：相同 appId 下语义高度相似的请求（如用户连续微调措辞重试），跳过重复的索引扫描与记忆召回。

```text
key   = sha256(appId + 归一化意图 + 工作区签名)
value = 已装配的 AiContextPack 摘要引用
存储  = Caffeine（进程内，已有依赖），TTL 10m，上限 2000
```

命中条件必须**三者全等**，工作区签名变化即失效 —— 复用已有 `WorkspaceSemanticIndex.workspaceSignature()`。

预期收益：连续重试场景省掉一次全量索引扫描（实测该扫描在中型 Vue 工程约 300~800ms）。

命中率需上报 `generation_context_cache_hits_total`，低于 5% 说明 key 设计过严，应下线而非硬留。

### 16.6 新增：会话级上下文压缩

当前 `CompletedToolCallContextCompactor` 只裁剪已完成写文件的参数，长任务（HEAVY 24 轮）仍会顶窗口。

新建 `orchestration/context/ConversationCompactor`：

触发阈值：已用 token > `模型窗口 × 0.6`。

压缩策略（按顺序，先保后压）：
1. **永不压缩**：system prompt、最近 2 轮、当前未完成工具调用、审批挂起上下文
2. 优先压缩：已完成工具的完整返回体 → 保留「路径 + 结果状态 + 关键行号」
3. 其次压缩：中间轮次的模型自然语言输出 → 小模型摘要为 ≤200 字进度账本
4. 最后压缩：早期轮次整体 → 单条「已完成 N 项：...」

压缩后必须写一条 `GENERATION_STAGE` 事件让用户可见（"已整理上下文，继续执行"），否则用户会觉得系统"忘事"。

**硬约束**：压缩是有损的，压缩结果必须能回答「已改哪些文件、还剩什么、当前证据是什么」三问，否则完成条件判定会失效。建议压缩后跑一次自检断言。

### 16.7 上下文工程验收

| 项 | 验收标准 |
| --- | --- |
| L3 情景记忆 | 每个终态任务必有且仅有一条记录，与积分结算同事务 |
| L3→L4 蒸馏 | 蒸馏产物可追溯 `supportingEpisodes`；`confidence<0.5` 不入库 |
| 分型召回 | 修复场景 `FAILURE_LESSON` 占比 ≥ 50% |
| 语义缓存 | 命中率 ≥ 5%；工作区变更后必失效（契约测试） |
| 会话压缩 | HEAVY 24 轮任务不再触发窗口溢出；压缩后三问可答 |
| 无回归 | 上下文命中率不降、首次构建通过率不降 |

---

## 17. 高级思考模式：让档位真正生效

### 17.1 当前问题

`GenerationThinkingMode{FAST, STANDARD, DEEP}` 已定义，`OpenAiThinkingPolicy` 已能下发，但**没有决策者**。等价于常量。

### 17.2 落地：由 ExecutionPlan 决定档位

思考档位不应由用户选，也不应固定，而应由 `IntentProfile`（第一部分 §6.1）推导后写入 `GenerationExecutionPlan`，执行期只读不改。

决策矩阵：

| 条件 | 档位 | 理由 |
| --- | --- | --- |
| `operationType=edit` 且 `expectedFileCount≤2` 且 `destructiveRisk=LOW` | FAST | 文案/样式改动，推理无收益 |
| `operationType=create` 且 `semanticComplexity=LOW` | STANDARD | 模板化创建 |
| `operationType=create` 且 `requiresBackend=true` | DEEP | 契约设计需要推理 |
| `semanticComplexity=HIGH` 或 `affectedScopes>3` | DEEP | 跨模块影响需要规划 |
| `confidence<0.6`（意图不明） | DEEP | 但应先触发澄清（§18） |
| 修复场景且 `repairRounds≥1`（首轮修复已失败） | DEEP | 浅层修复已证明不够 |
| 进入 `first-preview-completion-reserve` 窗口 | 强制降 FAST | SLA 优先，已有预留窗口机制 |

最后一条很关键：**思考档位必须能被 SLA 压制**。已有的 `GENERATION_<ROUTE>_FIRST_PREVIEW_COMPLETION_RESERVE` 机制正是压制点，DEEP 不能突破首预览承诺。

### 17.3 新增：修复场景的反思环

当前修复是「构建失败 → 喂错误 → 重生成」。首轮修复失败后仍是同样策略，缺少策略升级。

新建 `orchestration/verification/RepairStrategyEscalator`：

```text
第 1 轮修复：FAST + 仅错误信息 + 报错文件
第 2 轮修复：DEEP + 错误信息 + 报错文件 + 依赖文件
             + 第 1 轮改动 diff + L4 召回的 FAILURE_LESSON
第 3 轮：不再修复，回滚到 rollback_point，向用户报告
```

**关键约束**：第 2 轮必须显式告知模型「第 1 轮尝试了什么且失败了」，否则模型会重复同一个错误方案。这是当前修复成功率上不去的主要原因之一。

修复轮次上限沿用已有 `max-repair-rounds` 配置（CREATE=1、HEAVY=2），不新增配置项。

### 17.4 新增：独立评审 Agent（不共享上下文）

当前 `ReviewAgentNode` 是确定性节点，看不出语义问题（如"生成的登录页没有实际调用登录接口"）。

新建 `orchestration/verification/IndependentReviewAgent`：

- **独立会话**，不继承主 Agent 的 ChatMemory —— 避免主 Agent 的自我确认偏差
- 输入仅：用户原始意图 + 最终 diff + 关键产物文件
- 输出结构化：`{passed, blockers[], warnings[]}`
- 只读工具权限，不得写工作区
- 仅在 `validationRisk=HIGH` 或 `operationType=create` 时启用，避免给轻编辑加延迟

这是「多 Agent」在本项目里**真正有价值**的用法：独立上下文带来独立判断。与第一部分 §2.2 的第 3 层对应。

### 17.5 思考模式验收

| 项 | 验收标准 |
| --- | --- |
| 档位联动 | 单测覆盖 §17.2 全部 7 条决策路径 |
| SLA 压制 | 进入预留窗口后档位必降 FAST（契约测试） |
| 反思环 | 第 2 轮修复的 prompt 必含第 1 轮 diff 与失败原因 |
| 独立评审 | 评审 Agent 会话与主 Agent 隔离（不共享 memoryId） |
| 成本可控 | DEEP 占比 ≤ 30%，每成功任务 token 不上升超 15% |

---

## 18. 人在回路：从「审批」扩到「协作」

### 18.1 当前只有一种回路

`ToolApprovalService` 只处理破坏性工具（`FileDeleteTool`、`SnapshotRollbackTool`）。这是**风险回路**，不是**协作回路**。

缺三种：意图澄清、计划确认、交付验收。

### 18.2 新增回路一：意图澄清（最高优先）

触发：`IntentProfile.confidence < 0.6`，或检测到关键信息缺失。

```text
用户："做个商城"
  ↓ confidence=0.4，缺少：规模、是否要后台、是否要支付
  ↓
系统提问（≤3 个，每个带默认选项）：
  1. 需要商品管理后台吗？（默认：需要）
  2. 需要支付功能吗？（默认：先不做，留占位）
  3. 大概多少商品？（默认：中小规模，百级）
  ↓
用户回答 / 或 15s 无响应则采用默认值继续
```

**关键设计**：澄清必须**有默认值且可超时跳过**。纯阻塞式提问会让「快」的体验彻底崩掉 —— 宁可用默认值先出可预览结果，再让用户改。

实现落点：
- 新建 `orchestration/intent/IntentClarificationService`
- 复用已有 `ToolApprovalService` 的挂起/恢复/TTL 机制（`GenerationApprovalRequiredException` + `ToolApprovalExpirationCoordinator`），**不要新写一套挂起状态机**
- 事件类型：在 `GenerationStreamEvent` 新增常量 `INTENT_CLARIFICATION = "intent_clarification"` 与对应静态工厂方法，与现有 `AI_DELTA` / `GENERATION_STAGE` / `FIRST_PREVIEW_READY` 等常量风格一致；同时在 `GenerationPublicEventSanitizer` 补该类型的脱敏规则
- 超时默认值必须写入 `EpisodicRecord.intentDigest`，便于后续评估默认值是否合理

### 18.3 新增回路二：计划确认（仅 HEAVY）

触发：`route=HEAVY_EXPERT` 且 `expectedFileCount > 10`。

在 Planner 之后、Code 之前，把计划摘要推给用户：

```text
将要创建 14 个文件：
  · 前端 8 个（商品列表、详情、购物车、订单...）
  · 后端 5 个（Model/Repository/Service/Handler/Schema）
  · 配置 1 个
预计 3 分钟。[开始] [调整范围]
```

同样**默认继续**：8s 无响应自动开始。目的是给用户一个「叫停错误方向」的机会，而不是增加一道闸门。

### 18.4 新增回路三：交付验收信号

当前有 `GenerationFeedbackRepository`，但只在用户主动评分时才有数据。

补隐式信号（不打扰用户）：
- 交付后 5 分钟内用户又提改修请求 → `outcome=REWORKED`，写入 L3
- 交付后用户点了部署 → 强正向信号
- 交付后用户直接关闭 → 弱信号，不判定

这三个信号是 §16.3 蒸馏质量的**唯一真实标签源**。没有它们，蒸馏出的"有效方案"只是模型自说自话。

### 18.5 人在回路验收

| 项 | 验收标准 |
| --- | --- |
| 澄清超时 | 15s 无响应必须走默认值继续，不得挂死 |
| 计划确认超时 | 8s 无响应必须自动开始 |
| 挂起恢复 | 澄清/确认挂起后可跨节点恢复（复用现有租约 fencing） |
| 隐式信号 | REWORKED 判定准确率抽样 ≥ 90% |
| 体验不退化 | 澄清引入后 TTP p90 不上升超 10% |
| 中文 | 全部提问、选项、默认值说明为中文 |

---

## 19. 图编排：不要引入 LangGraph，补强自有 DAG

### 19.1 结论先行

**不建议引入 LangGraph。**

理由：
1. LangGraph 是 Python 生态；Java 侧 `langchain4j` 无对等实现，硬接会引入跨语言进程或自研移植，两者都是纯技术债。
2. 本项目 `GenerationDagRunner` 已具备 LangGraph 的核心价值：**依赖表达、检查点、恢复、指纹校验**。这些恰恰是最难写对的部分，而且已经写对了（含 `GRAPH_MISMATCH`、`AMBIGUOUS_NODE` 等恢复拒绝语义）。
3. 缺的只是三样：**并行执行、条件边、循环边**。这三样在现有 runner 上增量实现，代价远小于换框架。

需要的是 LangGraph 的**思路**，不是它的**依赖**。

### 19.2 缺口一：并行执行（最大提速点）

#### 19.2.0 先纠正一个常见误判

`GenerationDagRunner.run()` 第 96 行确实是顺序消费：

```java
for (GenerationAgentNode node : readyNodes) {   // ← 顺序
```

但**只改这个循环收益为零**。因为当前依赖声明是一条严格线性链：

```text
planner → template → context → architect → code → review → buildfix
（依赖声明取自各 AgentNode 构造器的 super(...) 第 4 参数）
```

| 节点 | 依赖声明 |
| --- | --- |
| `planner` | `List.of()` |
| `template` | `List.of("planner")` |
| `context` | `List.of("template")` |
| `architect` | `List.of("planner", "context")` |
| `code` | `List.of("architect")` |
| `review` | `List.of("code")` |
| `buildfix` | `List.of("review")` |

线性链意味着 `readyNodes` **恒为 1 个元素**，并行执行器无事可做。

> **因此真正的提速动作是「重新推导依赖」，而不是「改并行循环」。**  
> 顺序是依赖声明造成的结果，不是调度器造成的。先改声明，再改调度器；顺序颠倒则做完无收益。

#### 19.2.1 第一步：放松真实依赖

逐条核对哪些依赖是真的：

| 现有依赖 | 是否必需 | 结论 |
| --- | --- | --- |
| `template → planner` | 必需 | Template 需要 `targetType` 决定用哪个模板 |
| `context → template` | **仅创建场景必需** | 创建时需模板物化后才能扫描；**改修场景工作区已存在，无需等待** |
| `architect → planner, context` | 必需 | 需要意图与项目上下文 |
| 其余 | 必需 | 顺序语义真实存在 |

关键发现：`context → template` 这条边在**改修场景是伪依赖**。改修时（`hasGeneratedCode=true`）工作区早已存在，语义索引扫描可与 Planner、Template 并行。

而改修恰恰是最高频场景，且 `ContextAgentNode` 的索引扫描是准备阶段最慢的一步（中型 Vue 工程 300~800ms）。

#### 19.2.2 第二步：让依赖随计划变化

依赖不应是构造期常量，而应由 `GenerationExecutionPlan` 推导：

```java
/** 返回该节点在当前计划下的真实依赖。 */
default Set<String> dependencies(GenerationExecutionPlan plan) {
    return dependencies();   // 默认回退到静态声明
}
```

`ContextAgentNode` 实现：

```java
// 改修场景工作区已存在，索引扫描不依赖模板物化
return plan.isPatchFirst() ? Set.of() : Set.of("template");
```

改修场景下依赖图变为：

```text
planner ─┐
template ─┼→ architect → code → review → buildfix
context  ─┘
（planner / template / context 三者同批就绪）
```

此时 `readyNodes` 才会真正出现多元素，并行才有意义。

**注意**：动态依赖必须参与 DAG 指纹计算（见 §19.3 同样约束），否则恢复时 `bindGraph` 会误报 `GRAPH_MISMATCH`。建议指纹基于「按当前 plan 解析后的依赖图」，而非静态声明。

#### 19.2.3 第三步：才是改调度器

依赖放松后，按节点副作用属性分组执行。

`GenerationAgentNode` 接口新增：

```java
/** 节点是否无副作用，可与同批就绪节点并行执行。 */
default boolean sideEffectFree() { return false; }
```

标注为 `true` 的节点：
- `ContextAgentNode` — 只读索引扫描（改修场景）
- `PlannerAgentNode` — 纯计算

保持 `false`：`TemplateAgentNode`（模板物化写工作区）、`ArchitectAgentNode`、`CodeAgentNode`、`ReviewAgentNode`、`BuildFixAgentNode`。

**但 Planner 与 Template 之间存在真实的状态写读关系**，不能简单同批并行：

```text
PlannerAgentNode  第 46 行  context.setTargetType(...)
TemplateAgentNode 读 context.getTargetType() 决定模板
```

所以 §19.2.2 的改修场景依赖图要更精确 —— 可并行的是 **`context` 与 `{planner → template}` 这条子链**，而非三者两两并行：

```text
┌ planner → template ┐
│                    ├→ architect → code → review → buildfix
└ context ───────────┘
```

即 `context` 与 Planner/Template 子链并行。这仍能吃掉索引扫描的全部耗时（它是最慢的一步），收益基本等同于三者全并行。

执行逻辑：

```text
readyNodes 分为 [并行安全] 与 [需串行]
  → 并行安全组：提交到已有有界执行器，await 全部完成
  → 需串行组：保持逐个执行
```

**硬约束**（否则会引入难查的并发 bug）：
1. **不得新建线程池**。必须复用 Heavy 代已持有的有界执行器许可 —— 现有注释已明确禁止绕过全局资源治理。
2. `GenerationAgentContext` 的**部分**状态已受保护，部分没有，并行前必须分别处理：
   - 已安全：`putArtifacts` / `getArtifact` / `getArtifactValue` / `recordTiming` 均为 `synchronized`，artifacts 与 timings 无需改并发容器。
   - **不安全**：`targetType` / `upgradeRequired` / `qualityGateResult` / `workspaceIndexSnapshot` 是 Lombok `@Setter`，无同步也无 `volatile`。而 `PlannerAgentNode` 写 `setTargetType`、`ContextAgentNode` 写 `setWorkspaceIndexSnapshot` —— 这两个节点正是计划并行的节点，会产生可见性竞争。
   - **不安全**：`GenerationDagRunner` 中 `context.getTask().getArtifacts().put(...)` 与 `getTimings().put(...)` 直接改 task 自身的 Map，绕过了 context 的 `synchronized` 保护。
   
   推荐方案：并行节点**只返回 `AgentNodeResult`，不直接写 context**，由主线程在 await 后统一合并（写 context、写 task、记 timing）。这样无需引入任何同步原语，且合并顺序确定、可测。若确实需要节点内写状态，则相关字段必须改为显式同步访问器。
3. 检查点写入必须仍在主线程串行，不得并行 `taskStore.save()`。
4. 任一并行节点失败 → 取消同批其余节点 → 走现有失败检查点路径。
5. 并行不得跳过 `assertCanContinue()` 的取消检查。

预期收益：改修场景下 Context 索引扫描（300~800ms）与 Planner/Template 子链重叠，准备阶段省掉其中较短的一侧。

**收益边界要说清**：这只优化准备阶段，不优化模型生成本身。若准备阶段占 TTP 比例本就很低（如 HEAVY 路径生成耗时 3 分钟），则整体收益有限。**实施前应先用 Latency Ledger 确认准备阶段在各路由的实际占比**，占比 < 10% 的路由不值得做。LIGHT_EDIT / AGENT_EDIT 占比高，是主要受益方。

### 19.3 缺口二：条件边

当前节点选择是硬编码 `selectNodes(heavyPath)` 里的 if。新增验证节点、评审节点后会膨胀成大 switch，违反开闭原则。

改为节点自述准入条件：

```java
/** 该节点在当前计划下是否需要执行。 */
default boolean shouldExecute(GenerationExecutionPlan plan) { return true; }
```

`selectNodes` 退化为「全部注册节点 → 按 `shouldExecute(plan)` 过滤」。新增节点只需实现接口并注册为 `@Component`，不改编排器 —— 这才是开闭。

**注意**：`shouldExecute` 的结果必须参与 DAG 指纹计算，否则恢复时图不匹配会被 `bindGraph` 拒绝。建议把过滤后的节点集合指纹化，而非全量注册集合。

### 19.4 缺口三：循环边（修复环）

修复当前在 `BuildFixAgentNode` 内部循环，对 DAG 不可见 —— 所以检查点无法在「第 2 轮修复」处恢复，只能整体重跑。

改造：把修复表达为 DAG 的显式回边。

```text
Code → Review → Build ─┬─ 通过 → Commit
                        └─ 失败 → Repair → Build（回边，最多 N 轮）
```

实现：`GenerationDagRunner` 支持节点返回 `nextNodeHint`，配合已有 `max-repair-rounds` 做环上限。每轮修复都是独立可检查点节点，命名带轮次（`repair_1` / `repair_2`），避免 `AMBIGUOUS_NODE` 恢复冲突。

收益：修复中断后可从失败那轮恢复，不用重跑整个生成。对 HEAVY 路径（15m 总超时）意义显著。

### 19.5 图编排验收

| 项 | 验收标准 |
| --- | --- |
| 并行正确性 | 并发压测 100 次无 artifacts 丢失 / timings 错乱 |
| 并行不越权 | 不新建线程池（代码审查 + 无新 `Executors.` 调用） |
| 并行可取消 | 并行执行中取消，全部子节点在 grace 内收口 |
| 条件边 | 新增一个节点不修改 `AgentGenerationOrchestrator` |
| 指纹一致 | 条件边过滤后指纹稳定，恢复不误报 GRAPH_MISMATCH |
| 循环边 | 第 2 轮修复失败可从 `repair_2` 恢复，不重跑生成 |
| 提速证据 | Benchmark 证明准备阶段 p90 降低 ≥ 25%，质量指标不降 |

---

## 20. 流程稳定性：补齐剩余缺口

第一部分已列 P0 幂等问题。此处补充与本部分能力相关的稳定性要求。

### 20.1 新增能力的失败必须降级，不得阻塞

每项新增能力都是**可选增强**，故障时主链路必须继续：

| 能力 | 故障时行为 |
| --- | --- |
| L3 情景记忆写入 | **不适用降级** —— 见下方说明 |
| L3→L4 蒸馏失败 | `distilledAt` 保持 NULL，下轮重试，不影响生成 |
| 语义缓存故障 | 视为未命中，走完整装配 |
| 会话压缩失败 | 保留未压缩上下文；若已溢出窗口则按现有失败路径处理 |
| 澄清服务故障 | 直接采用默认值继续，不挂起 |
| 独立评审故障 | 降级为现有确定性 Review，记录降级原因 |
| 并行执行异常 | 可配置开关回退顺序执行 |

**关于 L3 写入的设计修正（2026-07-29）**

初版同时写了两条互相矛盾的要求：
- §16.2：L3 写入与积分结算同事务，避免「扣了费没有复盘」
- §20.1：L3 写入失败只记 WARN，不阻塞主链路

同事务意味着 L3 失败会回滚积分结算，两者不可兼得。

**解法：不引入独立写入，把 L3 字段折叠进现有终态 UPDATE。**

`DefaultGenerationTracePersistenceService.completeRunningTask` 本就要 UPDATE `generation_task` 这一行以写入 `status`/`endTime`/`durationMs`。L3 字段同属该行，在同一条 UPDATE 里一起写：

- 不存在「独立的 L3 写入」，因此不存在「L3 写入失败」这一故障模式
- 天然与积分结算同事务，满足 §16.2
- 不增加任何数据库往返，无性能代价
- fencing 与乐观锁语义完全复用，无需新增并发控制

代价：`completeTask` 签名需接受一个可空的 outcome 质量值对象。可空是必要的 —— `thinkingMode` 依赖 §17.2 落地、`firstPreviewMillis` 依赖首预览事件，二者未就绪时传 `null`，字段保持 NULL 表示未采集。

**所有新增能力都必须带独立开关**，默认关闭，灰度开启 —— 与现有 `GENERATION_MEMORY_CONTEXT_PARALLEL_READS_ENABLED` 的惯例一致。L3 字段写入是例外：它不是独立能力而是既有 UPDATE 的字段扩展，无开关，靠「可空即未采集」自然降级。

### 20.2 新增能力的资源必须有界

沿用项目既有风格，每项新增都要显式上限：

| 配置项 | 建议默认 | 说明 |
| --- | --- | --- |
| `MEMORY_DISTILL_ENABLED` | `false` | 蒸馏开关 |
| `MEMORY_DISTILL_MIN_EPISODES` | `5` | 触发蒸馏的最小情景数 |
| `MEMORY_DISTILL_MIN_CONFIDENCE` | `0.5` | 入库置信度门槛 |
| `MEMORY_DISTILL_MAX_OUTPUT_PER_RUN` | `3` | 单轮最多产出语义记忆条数 |
| `CONTEXT_SEMANTIC_CACHE_ENABLED` | `false` | 语义缓存开关 |
| `CONTEXT_SEMANTIC_CACHE_MAX_ENTRIES` | `2000` | 缓存容量 |
| `CONTEXT_SEMANTIC_CACHE_TTL` | `10m` | 缓存 TTL |
| `CONVERSATION_COMPACT_ENABLED` | `false` | 会话压缩开关 |
| `CONVERSATION_COMPACT_TRIGGER_RATIO` | `0.6` | 触发阈值（占窗口比例） |
| `INTENT_CLARIFICATION_ENABLED` | `false` | 澄清开关 |
| `INTENT_CLARIFICATION_TIMEOUT` | `15s` | 澄清超时 |
| `INTENT_CLARIFICATION_MAX_QUESTIONS` | `3` | 最多提问数 |
| `PLAN_CONFIRMATION_ENABLED` | `false` | 计划确认开关 |
| `PLAN_CONFIRMATION_TIMEOUT` | `8s` | 确认超时 |
| `INDEPENDENT_REVIEW_ENABLED` | `false` | 独立评审开关 |
| `DAG_PARALLEL_EXECUTION_ENABLED` | `false` | DAG 并行开关 |

生产 profile 需为其中的持久化项做启动校验（参照现有 Milvus 生产强制校验的写法）。

### 20.3 观测指标补充

新增能力必须可观测，否则无法判断该不该保留：

```text
generation_episodic_records_total{outcome}
generation_memory_distill_runs_total{result}
generation_memory_distill_output_total{type}
generation_context_cache_hits_total / _misses_total
generation_conversation_compactions_total{trigger}
generation_clarifications_total{result="answered|defaulted|failed"}
generation_plan_confirmations_total{result}
generation_independent_reviews_total{verdict}
generation_dag_parallel_batches_total
generation_dag_parallel_batch_size
generation_thinking_mode_total{mode,route}
generation_repair_escalations_total{round}
```

### 20.4 稳定性验收

| 项 | 验收标准 |
| --- | --- |
| 全部新能力可关闭 | 每项开关关闭后行为与当前基线完全一致 |
| 故障降级 | 逐项注入故障，主链路成功率不下降 |
| 资源有界 | 每项均有上限配置且有边界测试 |
| 生产校验 | 生产 profile 缺失必填配置时 fail-fast |
| 无新线程池 | 代码审查确认复用既有执行器 |

---

## 21. 实施优先级（本部分，按依赖排序）

依赖关系明确，**不可乱序**：

```text
第一部分 P0（幂等/中文/契约）
        ↓
IntentProfile ─────────┬──→ 思考模式联动（§17.2）
（第一部分 P1）          ├──→ 意图澄清（§18.2）
        ↓               └──→ 分型召回（§16.4）
GenerationExecutionPlan
        ↓
        ├──→ 计划确认（§18.3）
        ├──→ 独立评审（§17.4）、反思环（§17.3）
        └──→ DAG 动态依赖（§19.2.2）──→ DAG 并行（§19.2.3）──→ 条件边/循环边（§19.3/§19.4）

L3 情景记忆（§16.2）──→ 隐式验收信号（§18.4）──→ 经验蒸馏（§16.3）

【无前置依赖，可立即开工】
Latency 占比观测、L3 情景记忆、语义缓存（§16.5）、会话压缩（§16.6）
```

### 阶段 A：无依赖，可立即开工（1 周）

| 项 | 章节 | 收益 | 风险 |
| --- | --- | --- | --- |
| Latency Ledger 分路由拆解准备阶段占比 | §19.2.3 | 决定并行是否值得做 | 无，纯观测 |
| L3 情景记忆表与写入 | §16.2 | 为后续一切评估打底 | 低 |
| 语义缓存 | §16.5 | 重试场景省一次全量扫描 | 低 |
| 会话级压缩 | §16.6 | HEAVY 长任务不再溢出 | 中，有损压缩需自检 |

**先做观测再做并行**：DAG 并行依赖 ExecutionPlan（动态依赖需要 plan 才能推导），且收益取决于准备阶段实际占比。先量出占比，再决定要不要投入 —— 见阶段 C。

**L3 情景记忆优先级最高**：它不依赖任何新概念，却是后续蒸馏、隐式信号、路由校准、Benchmark 归因的共同数据底座。越早开始积累越好。

### 阶段 B：依赖 IntentProfile（1~2 周）

| 项 | 章节 | 前置 |
| --- | --- | --- |
| 思考模式联动 | §17.2 | IntentProfile + ExecutionPlan |
| 意图澄清回路 | §18.2 | IntentProfile |
| 分型召回 | §16.4 | IntentProfile |
| 修复反思环 | §17.3 | ExecutionPlan |

### 阶段 C：依赖 L3 数据积累（2~4 周）

| 项 | 章节 | 前置 |
| --- | --- | --- |
| 隐式验收信号 | §18.4 | L3 情景记忆 |
| 经验蒸馏 L3→L4 | §16.3 | L3 + 隐式信号（需真实标签） |
| 独立评审 Agent | §17.4 | ExecutionPlan |
| 计划确认 | §18.3 | ExecutionPlan |
| DAG 动态依赖 + 并行 | §19.2 | ExecutionPlan + 阶段 A 的占比观测 |
| DAG 条件边 + 循环边 | §19.3 / §19.4 | ExecutionPlan + DAG 并行 |

**蒸馏必须等隐式信号就绪**。没有 REWORKED 标签就去蒸馏"有效方案"，等于让模型给自己发奖状，会持续污染向量库。

---

## 22. 本部分不做的事（明确排除，避免技术债）

1. **不引入 LangGraph** —— 理由见 §19.1。补强自有 DAG。
2. **不把确定性节点模型化** —— Planner/Template/Context 保持规则实现，模型化只会增加延迟与故障面。
3. **不新建线程池** —— 复用既有有界执行器。
4. **不做多轮阻塞式对话** —— 所有人在回路必须有默认值与超时，否则"快"的体验崩塌。
5. **不把原始 prompt / 文件内容写入 L3 / L4** —— 沿用现有 untrusted evidence 边界。
6. **不为新能力新建 outbox / 挂起状态机 / 租约机制** —— 复用现有实现，这些是最容易写错的部分。
7. **不默认开启任何新能力** —— 全部开关默认关闭，Benchmark 证明收益后灰度。

---

## 23. 给开发 agent 的执行须知

1. **每项改动必须能独立回滚**：开关关闭后行为与基线完全一致，并有测试证明。
2. **先写契约测试再写实现**：本项目已有 2401 个测试，新增能力必须同步补测试，尤其是边界与故障注入。
3. **中文门禁**：新增的异常消息、用户提示、事件摘要、注释全部中文。英文仅限代码标识符。
4. **不留 TODO**：不接受"先 hardcode 后面再清"。做不完就不合并。
5. **不扩大 Coordinator**：`HeavyGenerationCoordinator` 已 831 行职责过宽（第一部分 §4.3.B）。新能力不得往里加，应作为独立深模块由编排层组合。
6. **配置随代码走**：新增配置项同步更新 `application.yml`、`README.md` 配置表、`docs/backend-production-configuration.md`。
7. **验收以 Benchmark 为准**：不接受"感觉更快了"。速度类改动必须有 p90/p99 对比，且质量指标不得回退。
8. **提交前**：`.\mvnw.cmd test` 全绿；涉及前后端契约时同时跑前端构建。
