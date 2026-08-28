# 后端 AI 生成项目链路改造总清单

> 文档类型：持续维护的改造台账（Reference + Explanation）
>
> 审计基线：`dev@850f6e6`（2026-08-27）+ 当前未提交工作树
>
> 本次结论：停止继续横向堆功能，先闭合事实所有权、零副作用、能力协商、完成证据和恢复安全五条不变量。
>
> 重要口径：代码已提交、自动化测试通过、真实模型验收、真实浏览器验收、生产流量验收是五种不同证据，不得互相替代。

## 1. 这份清单解决什么问题

项目已经进行了多轮生成链路改造，提交数量很多，能力面也明显扩大。现在最大的风险不再是“少一个类或少一条分支”，而是跨模块对同一事实给出不同答案，例如：

- 路由判断可以选择某模式，但准入阶段才发现没有对应 Pipeline；
- 场景要求的是 `BUILD`，完成证据却没有证明构建实际发生；
- 名义上的 READ_ONLY 不发布项目，却在读取上下文时写入语义索引；
- 实时链路知道具体失败原因，刷新后的持久终态却只剩“项目生成失败”；
- 快照目录存在，但快照身份没有完整绑定应用、工程类型、子目录和任务执行纪元；
- 代码里已经有 Benchmark、晋级门禁和消融框架，但还没有足够的真实模型样本证明策略更好。

因此，本清单不是再造一套架构，也不是罗列所有可做的优化，而是：

1. 给端到端链路建立唯一状态台账；
2. 明确哪些能力已经工程落地、哪些仍待真实验收、哪些只是工作树 WIP；
3. 用少量高杠杆改造消除隐藏 Bug 和多事实源；
4. 让后续每一轮工作都能回答“解决了哪个用户问题、由什么证据证明、边界在哪里”。

## 2. 北极星、工程原则与硬性不变量

### 2.1 北极星

北极星不是后端 `SUCCESS` 数量，而是“有效交付率”：

```text
有效交付 =
终态成功
∧ 用户意图覆盖完整
∧ 场景要求的验证被真实执行并通过
∧ 预览或运行结果真实可访问
∧ 未在约定观察窗口内因同一问题返工
```

围绕北极星只保留三组一等指标：

| 目标 | 核心指标 | 禁止的替代指标 |
|---|---|---|
| 用户体验 | 首个有意义响应、首个可用预览、总耗时、取消生效时间、失败可解释率、安全恢复成功率 | 只看平均响应时间、只看 SSE 是否发出 |
| 生成质量 | 有效交付率、意图覆盖率、一次构建通过率、运行时通过率、假成功数、同因返工率 | 只看模型输出长度、只看“生成了文件” |
| 生成效率 | 单位有效交付 Token、积分、Provider 成本、工具轮次、构建次数、上下文重复读取量 | 只看单次模型调用便宜、只看某一步更快 |

所有指标必须按 `工程类型 × 场景 × route × releaseIdentity` 分桶；全局平均值只能用于总览，不能用于策略晋级。

### 2.2 工程原则

1. **单一事实源**：场景、能力、验证结果、工作区身份、成本和终态各有且只有一个权威所有者。
2. **观察事实高于计划事实**：`expected BUILD` 不能生成 `BUILD passed`；只有 Validator 的观察结果可以产生验证证据。
3. **生成内容默认不可信**：仓库文件、模型输出、依赖清单、脚本、快照和恢复制品都必须经过边界校验。
4. **深模块与开闭原则**：新增工程类型或场景通过 Capability、Adapter、Manifest、Validator、Benchmark 扩展，不新增下游散落分支。
5. **低风险自治，高风险确认**：只读与可逆操作可自动执行；网络、依赖、破坏性修改、恢复覆盖等跨边界行为需策略或审批。
6. **持久状态优先**：任务、租约、执行纪元、发布日志、终态意图和工具副作用必须支持进程退出和跨实例恢复。
7. **失败关闭**：证据缺失、身份不一致、版本未知、Sandbox 不可用或结果不确定时，宁可明确失败，也不能猜测成功或盲目重放。
8. **为有效交付优化**：任何性能或成本优化都不能以降低真实质量、扩大安全边界或隐藏失败为代价。

### 2.3 必须永久成立的不变量

- 假成功数为 0。
- READ_ONLY 对项目工作区的字节级变更数为 0。
- 未经实际 Validator 观察，不得生成 `FAST/BUILD/RUNTIME/EXPERT passed` 证据。
- 不存在“路由允许、准入拒绝、但本可安全回退”的能力断层。
- 旧执行纪元不得写入、发布、回滚或终态化新执行纪元。
- 发布成功但业务终态永久缺失数为 0。
- 跨应用、跨工程类型、跨 scope、跨任务的快照恢复数为 0。
- 取消或时限到达后不得继续新增模型、工具、构建或发布副作用。
- 任何策略没有真实质量、延迟和单位成功成本证据时不得晋级。
- 工作树里的 WIP 不得记为“已完成”。

## 3. 边界：本轮做什么，不做什么

### 3.1 纳入范围

```text
任务提交
→ 鉴权 / 应用所有权 / 限流 / 幂等
→ 意图预检 / 场景决策 / 能力协商 / 预算报价
→ 容量与积分准入 / Durable Task
→ Worker 领取 / Lease / Epoch / Fence / 隔离工作区
→ CREATE | READ_ONLY | LIGHT_EDIT | AGENT_EDIT | HEAVY_EXPERT
→ 上下文检索 / 模型调用 / 工具 / 审批 / Continuation
→ FAST / BUILD / RUNTIME / EXPERT 验证与预览
→ 完成证据门禁
→ 版本化发布 / Journal / Reconciliation
→ 业务终态 / SSE / 计费结算 / 清理
→ Benchmark / 反馈 / 场景归因 / 策略晋级
```

鉴权、应用生命周期、安全、成本、租户容量和可观测性虽然是横切能力，但直接决定生成链路是否可运营，属于本清单范围。

### 3.2 明确不做

- 不整体重写现有编排框架，不因“看起来更 Agent”引入新工作流引擎或微服务。
- 不复制 Claude Code/Codex 的界面、命名或 Agent 数量；只借鉴经过验证的机制。
- 不继续增加无独立上下文、无明确权限、无质量收益证明的“子 Agent”。
- 不建设基础模型训练、在线强化学习、大规模向量聚类或完整 MLOps 平台。
- 不建设完整支付、订阅和动态套餐系统；本轮只完善生成任务的预算、计费解释和管理边界。
- 不做与生成链路无关的全站 UI 重构；仅定义后端需要提供的稳定体验合同。
- 不为尚未声明支持的框架写临时 `if/else`；新增类型必须同时有 Capability、Adapter、Validator 和 Benchmark。
- 不把单测、编译、Mock Sandbox 或提交记录描述成真实模型、真实浏览器或生产验收。
- 不把用户工作树现有的并行改动顺手重构或合并到本清单提交。

## 4. 状态与证据口径

### 4.1 状态

| 标记 | 含义 |
|---|---|
| ✅ 工程完成 | 已进入 Git 历史，并有对应测试/契约资产；不自动代表真实环境完成 |
| 🟡 待验收 | 主体代码已提交，但仍缺集成、故障注入、真实模型、浏览器、并发或生产指标 |
| 🚧 进行中 | 当前工作树已有实现或测试，但尚未形成独立提交与完整验证，不能计入交付 |
| ⬜ 待办 | 已确认的问题或必要能力，当前没有完成证据 |
| 🧊 暂停 | 有基础设施，但在获得数据前停止继续投入 |
| ⛔ 范围外 | 本轮明确不做，防止改造失控 |

### 4.2 证据等级

| 等级 | 证据 | 可以证明什么 |
|---|---|---|
| E0 | 设计、ADR、清单 | 边界与决策被写清楚，不能证明代码可用 |
| E1 | 独立提交、可审查 Diff | 实现已进入版本历史，不能证明行为正确 |
| E2 | 单元、契约、架构测试 | 局部不变量成立，不能证明跨进程或真实外部系统 |
| E3 | 集成、并发、故障注入、真实 OS/容器 | 跨模块恢复和运行边界成立 |
| E4 | 真实模型、浏览器、Backend/Full Stack Benchmark | 生成质量与体验在代表性场景成立 |
| E5 | 灰度/生产指标、告警与回滚演练 | 在真实流量与故障下可运营 |

状态更新必须同时记录证据等级。例如“✅ E2 / 🟡 E4-E5”表示工程契约已完成，但真实质量与生产体验仍待验收。

## 5. 当前链路与事实所有权

| 阶段 | 权威事实 | 应由谁拥有 | 下游只能做什么 |
|---|---|---|---|
| 提交 | user、tenant、app、idempotency key | API/Admission | 校验和引用，不得重新识别所有权 |
| 预检 | IntentProfile、目标类型、资源需求 | Scenario Decision Kernel | 消费冻结事实，不再解析 Prompt |
| 路由 | mode、fallback、validation floor、rule/release fingerprint | Scenario Decision + Capability Negotiation | 选择已声明可执行的 Pipeline |
| 准入 | 并发、租户预算、积分预授权、deadline | Admission Policies | 领取已获准任务，不重新报价 |
| 执行 | taskId、leaseOwner、epoch、fence、workspace identity | Durable Runtime | 所有副作用携带并重验 fence |
| 上下文 | 来源、路径、版本、敏感度、Token 预算 | Repository Context Trust/Retrieval | 模型只接收受保护的 Context Envelope |
| 工具 | 每项成功/失败、有效 mutation、side-effect receipt | Tool Runtime | 模型文本不能反向伪造工具事实 |
| 验证 | 实际执行级别、观察结果、日志摘要、主体身份 | Validator | Completion 只消费 observed evidence |
| 发布 | candidate、active pointer、journal、reconcile status | Publication Service | 业务终态引用发布结果，不自行移动目录 |
| 终态 | status、failure category、delivery receipt、cost settlement | Finalization/Outbox | SSE、查询、反馈共享同一持久投影 |
| 学习 | scenario bucket、release identity、quality/latency/cost | Evaluation/Attribution | 只有完整样本可进入晋级判断 |

## 6. 当前完成面与审计结论

从 `53d0657` 到代码基线 `f0b6645` 共有 165 个提交。它们证明项目已从“单条生成流程”发展成有场景、能力、工作区安全、持久任务、验证、发布和评测边界的系统；但提交数量不等于链路已经验收完成。

| 领域 | 当前状态 | 已有基础 | 仍未闭环的核心问题 |
|---|---|---|---|
| 场景决策 | ✅ E1-E2 / 🟡 E4-E5 | 冻结 `GenerationScenarioDecision`、有界澄清、发布指纹、下游去 Prompt 重解释 | 澄清默认关闭；缺真实中文歧义样本和生产路由准确率 |
| READ_ONLY | ✅ P0 已闭环 / 🟡 E3-E5 | 独立 Pipeline、零工作区写入、EXPLAIN/AUDIT/PLAN 分证据合同、统一 Repository Context Trust | 待真实恶意仓库样本、内存/并发基线和跨平台验收 |
| Pipeline 能力与 OCP | ✅ P0 已闭环 | 从真实 Pipeline Bean 构建 Capability Registry，并由决策内核在准入前协商可执行 route | 待真实流量统计，不新增第二份工程类型集合 |
| Agent/工具协议 | ✅ P0 已闭环 / 🟡 E3-E5 | 回合预算、逐项工具事实、有效 mutation、Observed Validation Evidence 与分场景完成证据 | 待真实 Provider、失败注入和长会话验收 |
| 路由回退归因 | ✅ P0 已闭环 | CREATE/LIGHT/AGENT 回退后统一使用 effective request 持久化路由、成本、事件和 release identity | 待跨实例压力与真实成本报表验收 |
| 工作区 Trust/Sandbox | ✅ E1-E2 / 🟡 E3-E5 | 控制文件、生命周期脚本、依赖源、锁文件、环境、网络、registry egress、Repository Context Trust 与执行前复核 | 统一代码边界已落地；真实恶意仓库、OS 资源上限和攻击演练待补 |
| 验证/完成/发布/终态 | ✅ E1-E2 / 🟡 E3-E5 | 强类型制品、完成门禁、Lease/Fence、Publication Journal、Reconciler、Terminal Effect Receipt；E2 故障矩阵已统一 | 缺真实 kill/DB/Redis/磁盘/移动窗口 E3 证据；刷新后终态仍过度泛化 |
| 快照/回滚 | ✅ P0 代码闭环 / 🟡 跨平台补证 | UUID 自包含 bundle、Manifest/provenance/tree hash、v2 制品、统一消费者与恢复前 fail-closed；Manifest/树篡改已纳入 E2 矩阵 | 当前 Windows 有 3 项 symlink 测试因权限跳过，仍需支持环境的跨平台补证 |
| 成本/容量 | ✅ E1-E2 / 🟡 E3-E5 | 预授权、Provider 调用账本、用户计费、成功交付成本、租户并发/月预算 | 用户看不到预计上限、实际扣费和退还；缺跨实例公平压测和租户管理员视图 |
| Benchmark/学习 | ✅ E2 样本 / 🟡 E3-E5 | v3.5 Dataset 共 59 条；15 个已声明的 route × 工程类型单元均不少于 3 条 Fixture，提示注入、秘密文件、部分读取、Vue→Full Stack 升级与 Fallback 已有确定性评分；取消、审批、恢复和发布故障均有绑定持久身份与可执行测试的 E2 样本；真实 Selenium 探针已有独立 E3 smoke | 尚缺真实模型、端到端浏览器、Backend/Full Stack 基线、真实故障注入与线上关联 |
| 预览/进度 | ✅ 工程 / 🟡 E4-E5 | 任务级暂定预览、已验证预览、ETA、可重放 SSE 与 durable terminal | 待真实浏览器、多任务隔离、断线重连和资源回收验收；旧“约 5 秒即关闭”结论已失效 |
| 安全/应用治理 | ✅ 基础 / 🟡 E3-E5 | 匿名限流、登录轮换 session、SQL 门禁、应用删除门禁、CSP、预览 WebSocket 边界 | 需端到端 RBAC/所有权矩阵、威胁模型、审计事件、租户/应用控制面和真实攻防验收 |

### 6.1 已进入 Git 历史、可以保留的方向

- `d797919`、`8df92f0`：场景决策冻结和入口收口。
- `a6f6271` 及后续只读证据提交：独立 READ_ONLY 是正确架构方向，但零写与 PLAN 证据契约尚未闭合。
- `4b93452`：从实际 Pipeline 声明构建准入能力矩阵，应该继续作为能力唯一事实源。
- `505ebe7`、`9e0e534`、`ad54da6`、`a951d61`、`71a669a`、`2524331`、`fd88432`、`548fceb`：统一生成工作区 Trust 与执行边界。
- `85edd0e`、`3944404`、`aaabaed`：按质量、尾延迟和单位成功成本约束策略晋级。
- `b74b074`、`3e94cdf`、`68097a9`、`850f6e6`：发布、对账、回滚不确定性的安全收口。
- `7488f66`、`647d50e`：工具逐项成功与有效 mutation 成为可信事实。
- `31cf6fc`、`2835f96`：语义索引缓存资源有界，READ_ONLY 零写及分场景证据合同闭环。
- `e31082e`、`cac06c0`、`fc86efc`：Observed Validation、统一项目上下文信任边界和 fallback effective request 归因闭环。
- `1abd79b`：路由从实际 Pipeline Capability Registry 协商可执行能力，不再维护手写 Catalog。
- `c338e14`、`2874c2e`、`3ace480`：稳定目录指纹、不可变快照身份、自包含 bundle、v2 回滚制品、全部消费者迁移及分层装配约束。
- 工程类型 Adapter、Template、Recipe、Runtime Validator 的注册化方向应保留，禁止退回大 `switch`。

### 6.2 关键证据入口

- 场景唯一事实：[GenerationScenarioDecision.java](../src/main/java/com/rush/rushaicodemother/orchestration/decision/GenerationScenarioDecision.java)
- READ_ONLY 强制文件依据：[ReadOnlyAnalysisService.java](../src/main/java/com/rush/rushaicodemother/orchestration/readonly/ReadOnlyAnalysisService.java)
- READ_ONLY 隐式索引写入：[WorkspaceSemanticIndexService.java](../src/main/java/com/rush/rushaicodemother/orchestration/index/WorkspaceSemanticIndexService.java)
- Pipeline 能力唯一注册表：[GenerationPipelineCapabilityRegistry.java](../src/main/java/com/rush/rushaicodemother/orchestration/pipeline/GenerationPipelineCapabilityRegistry.java)
- CREATE 当前支持类型声明：[SlotFillGenerationPipeline.java](../src/main/java/com/rush/rushaicodemother/orchestration/pipeline/SlotFillGenerationPipeline.java)
- 准入期能力拒绝：[GenerationTaskCapabilityAdmissionPolicy.java](../src/main/java/com/rush/rushaicodemother/orchestration/runtime/task/GenerationTaskCapabilityAdmissionPolicy.java)
- Agent Edit 期望/观察证据缺口：[AgentEditGenerationPipeline.java](../src/main/java/com/rush/rushaicodemother/orchestration/pipeline/AgentEditGenerationPipeline.java)
- Light Edit 空完成证据：[LightweightEditGenerationPipeline.java](../src/main/java/com/rush/rushaicodemother/orchestration/pipeline/LightweightEditGenerationPipeline.java)
- Fallback 后失败归因入口：[GenerationPipelineExecutor.java](../src/main/java/com/rush/rushaicodemother/orchestration/pipeline/GenerationPipelineExecutor.java)
- 公共任务状态字段：[GenerationTaskStatusVO.java](../src/main/java/com/rush/rushaicodemother/model/vo/GenerationTaskStatusVO.java)
- 持久终态泛化投影：[GenerationTerminalStreamEventFactory.java](../src/main/java/com/rush/rushaicodemother/orchestration/eventstream/GenerationTerminalStreamEventFactory.java)
- 当前 33 条评测数据：[generation-benchmark-dataset-v2.json](../src/main/resources/benchmark/generation-benchmark-dataset-v2.json)
- 快照 Manifest 与读取边界：[SnapshotManifest.java](../src/main/java/com/rush/rushaicodemother/orchestration/snapshot/SnapshotManifest.java)、[GenerationSnapshotWorkspaceService.java](../src/main/java/com/rush/rushaicodemother/orchestration/snapshot/GenerationSnapshotWorkspaceService.java)

## 7. 方向调整：当前只激活五个高杠杆改造包

在这五个改造包完成前，暂停新增工程类型、Agent 角色、路由规则和大规模性能重构。

**P0-0 执行门禁（不计入五个业务改造包）**：先冻结 `dev@850f6e6` 的提交态事实，为当前每组未提交改动登记 owner、文件范围、依赖、测试和目标提交；不重置用户改动，也不把 Snapshot、Index、Pipeline、Router、SQL 等不同主题揉成一次“全绿”提交。

| 顺序 | 改造包 | 当前状态 | 为什么先做 | 完成出口 |
|---|---|---|---|---|
| P0-1 | READ_ONLY + Repository Context Trust | ✅ | 已完成零写、分场景证据合同和统一 Context Trust | `31cf6fc`、`2835f96`、`cac06c0`；进入 E3-E5 验收 |
| P0-2 | Observed Validation Evidence + Fallback Attribution | ✅ | 已完成观察证据、完成门禁和 effective request 归因 | `e31082e`、`fc86efc`；进入故障注入与真实成本验收 |
| P0-3 | Capability Negotiation 单一事实源 | ✅ | 已删除重复 Catalog，并从 Registry 协商可执行 route | `1abd79b`；不支持组合在模型调用与计费前拒绝 |
| P0-4 | Snapshot Provenance 与恢复身份 | ✅ 代码 / 🟡 跨平台证据 | 已完成 UUID bundle、Manifest、v2 制品、树摘要与全部消费者迁移 | `c338e14`、`2874c2e`、`3ace480`；尚缺支持 symlink 的环境补证和完整故障矩阵 |
| P0-5 | 真实交付基线与故障矩阵 | ✅ E2 样本 / 🟡 E3-E5 | 能力矩阵、只读评分合同、跨类型/Fallback 与四类故障样本已建立；没有 E3-E5 数据仍无法判断路由或 Agent 改造收益 | 运行真实故障注入、模型/浏览器/Backend/Full Stack 基线 |

### 7.1 并行与文件边界

- P0-1 涉及 Context、Index、ReadOnly、Light/Agent Edit；不得与无界的“索引性能重构”混为一个提交。
- P0-2 涉及 Pipeline Outcome、Validator、Completion Evidence、Finalization；必须先冻结证据 DTO，再迁移生产者和消费者。
- P0-3 只解决“能力声明到可执行路由”的单一事实源；不得顺手改意图识别规则。
- P0-4 涉及 Filesystem、Snapshot、Rollback、Diff 消费者；与 P0-1 都接触文件系统，合并与测试需串行，避免互相掩盖。
- P0-5 先建立数据，不在拿到报告前调整 Prompt、模型池、规划 DAG 或路由阈值。
- 每个改造包独立 Diff、独立提交、独立回滚；不得把当前工作树其他并行修改一起计入完成。

## 8. 分领域改造清单

### A. 场景决策与能力协商

**目标**：一次理解，冻结事实，只选择真实可执行的路线；新增类型靠注册扩展，不靠修改多个中心类。

- [x] ✅ 冻结 `IntentProfile`、目标工程类型、可变性、资源需求、route、验证下限、工具权限和 release fingerprint。
- [x] ✅ EXPLAIN/AUDIT/PLAN 被映射到 READ_ONLY，写场景使用受 Fence 保护的写权限。
- [x] ✅ 低置信意图澄清被限制在 Preflight，禁止工具、构建和工作区副作用。
- [x] ✅ 从实际 Pipeline Bean 声明构建 `GenerationPipelineCapabilityRegistry`，重复声明启动失败。
- [x] ✅ 前端与后端能力合并为 Full Stack，避免线性枚举比较丢失已有能力。
- [ ] **P0** 让路由/场景决策消费 Registry 派生的只读能力视图或能力协商结果；不得再维护手写工程类型集合。
- [ ] **P0** 修复 HTML/MULTI_FILE 首次简单 CREATE：若无 CREATE implementation，应在预检选择 HEAVY 或明确零计费拒绝，不能先选 CREATE 再被 Admission 拒绝。
- [ ] **P0** 删除/拒绝当前工作树中的重复 `GenerationExecutionCapabilityCatalog` 设计，保留一个能力事实源。
- [ ] 为 `operation × mutability × codeGenType × mode` 的每个受支持单元建立契约测试；所有未支持单元证明在模型与计费前关闭。
- [ ] 明确 HTML、MULTI_FILE 的产品承诺：正式支持、受控降级，或从公开可选项移除；禁止“前端可选、执行时失败”。
- [ ] 在真实标注集上验证中文歧义、跨类型升级、数据库诉求、破坏性修改和范围估算；澄清开关灰度前保持默认关闭。

**验收出口**：相同请求在提交、排队、恢复、跨 Worker、Fallback 后的 Scenario fingerprint 不变；每个被选择的 route 都有唯一执行所有者；新增工程类型只新增 Adapter/Manifest/Test，不修改既有类型分支。

### B. READ_ONLY 与 Repository Context Trust/Retrieval

**目标**：只读是真的零副作用；不同只读场景拥有不同证据合同；仓库内容作为不可信数据进入模型。

- [x] ✅ READ_ONLY 独立 Pipeline 不注入写工具、Patch、构建或发布服务。
- [x] ✅ EXPLAIN/AUDIT 的文件引用必须落在已采集路径，越界行号降级或拒绝，不能让模型伪造文件依据。
- [ ] **P0 Bug** 修复 `ReadOnlyAnalysisService → AgentEditContextCollector → WorkspaceSemanticIndexService.loadOrBuild` 写入 `.ai-code-index/semantic-index.json` 的旁路。
- [ ] **P0 Bug** 按操作拆分证据合同：
  - EXPLAIN：已有工程时以文件/符号事实为主；空工程应明确“无工程可解释”，不能泛化编造。
  - AUDIT：必须有仓库事实与可定位证据；无可审计文件时返回结构化不可审计原因。
  - PLAN：允许以用户需求/冻结规格为依据，仓库引用可选；空项目或新项目规划不能因无文件稳定失败。
- [ ] 将索引移出用户项目工作区，放入 task/execution cache 或受控平台缓存；缓存身份至少绑定 workspace version/fingerprint。
- [ ] 建立 `RepositoryContextRequest → RetrievedEvidence → ProtectedContextEnvelope` 深模块，统一服务 READ_ONLY、LIGHT_EDIT、AGENT_EDIT 和 HEAVY。
- [ ] Context Envelope 记录来源、相对路径、内容指纹、版本、截断、敏感度、Prompt Injection 风险、Token 预算和是否允许出站。
- [ ] 统一复用 `AiContextBoundaryService` 或其演进模块，防止仓库指令冒充系统指令，拦截源码密钥、令牌、私钥和配置秘密出站。
- [ ] 消除 READ_ONLY/Light/Agent 对原始文件内容的重复拼接和重复扫描；性能优化只能发生在统一边界内部。
- [ ] 新增 EXPLAIN/AUDIT/PLAN 端到端测试：执行前后工作区树摘要完全一致，包含隐藏目录、空目录、mtime 策略和已存在索引场景。
- [ ] 新增恶意仓库样本：提示注入、伪系统消息、超大文件、二进制、符号链接、密钥、Unicode 路径和扫描期间文件变化。

**验收出口**：READ_ONLY 的 mutation、发布、数据库资源、构建、依赖安装均为 0；PLAN 可在空项目上产出有需求依据的计划；所有入模仓库上下文都有可审计来源且不泄漏秘密。

### C. Agent、工具与完成证据

**目标**：模型负责提出行动，工具和 Validator 负责提供事实；Fallback、恢复和终态始终保留真实执行路径。

- [x] ✅ Agent 回合、模型调用、工具调用、修复次数和时限具有统一预算。
- [x] ✅ 只读工具可受控并发，写工具保持串行；并发首错可取消慢调用。
- [x] ✅ 取消信号贯穿同步模型、工具、Go 构建和共享等待。
- [x] ✅ 工具失败协议区分调用失败、业务失败、部分成功和 no-op；模型可见 Markdown 不作为持久事实。
- [x] ✅ 批量读取只保存真实成功项；有效 mutation 由工具执行结果而非模型声明决定。
- [x] ✅ **P0 Bug** Agent Edit 已改为消费 `AgentEditVerificationOutcome` 的 observed evidence；Pipeline 不再按 `expectedValidationLevel` 构造或升格成功证据。
- [x] ✅ **P0 Bug** Light Edit 成功证据已由实际 `GenerationValidationObservation` 生成；无 Validator 观察时 evidence 保持为空并由完成门禁拒绝。
- [x] ✅ Fallback 后使用 `effectiveRequest` 完成失败终态、SSE、质量记忆、成本与 release fingerprint 归因，禁止回落到初始 request。
- [ ] 定义不可伪造链路：`Validator Observation → Typed Evidence → Completion Gate → Delivery Receipt`，禁止 Pipeline 手写“验证通过”文本。
- [ ] 为工具副作用建立稳定 receipt/idempotency key；Continuation 恢复时核对已完成工具，避免重复安装、重复写、重复回滚。
- [ ] 审批持久化已绑定 `taskId/requestExecutionEpoch/toolName/argumentsDigest`，批准、拒绝、过期、重复提交、执行恢复和越权已有持久状态机与测试；仍缺用户刷新后的查询/恢复合同验收。
- [ ] 对 Provider 超时、连接中断、已发送未确认等结果不确定场景定义重试策略；不得把未知调用当作未发生。
- [ ] 统一 failure taxonomy：低基数内部分类用于恢复和学习；公开 reason 单独脱敏，禁止用自由异常文本做指标标签。

**验收出口**：无观察即无证据；no-op、部分成功和空输出无法完成；Fallback 后所有持久事实指向最终实际 route；恢复不会重复不可逆副作用。

### D. 生成工作区 Trust、Sandbox 与供应链

**目标**：生成项目可以被验证和运行，但不能因此获得宿主机、凭证或任意网络权限。

- [x] ✅ 统一拒绝可改变安装行为的控制文件、危险生命周期脚本和非受信依赖源。
- [x] ✅ 安装、构建和运行前重新校验当前工作区，关闭历史残留和旁路写入。
- [x] ✅ 锁文件执行可信校验，依赖网络仅允许受信 registry，运行网络需显式授权。
- [x] ✅ 子进程环境最小化，不继承无关宿主环境；Benchmark 复用生产 Trust Policy。
- [x] ✅ 预览施加 CSP、鉴权和 WebSocket 应用边界。
- [ ] Sandbox 无法启动时 fail-closed；生产不得静默退化为宿主直接执行。
- [ ] OS/容器限制必须被所有子进程继承，覆盖孙进程、Shell、包管理器脚本和构建工具。
- [ ] 补齐 CPU、内存、进程数、磁盘、文件数、输出大小、运行时长和端口配额。
- [ ] 对 symlink、junction、hardlink、device file、FIFO、大小写碰撞和路径标准化做跨平台负测。
- [ ] 把网络策略、依赖 registry、密钥注入、运行权限做成显式 Capability，不由 Prompt 或生成文件放宽。
- [ ] 生成产物进入 Benchmark、预览、保存、发布和恢复时共享同一 Trust Kernel；用架构测试阻止 Pipeline 自建黑名单。
- [ ] 建立攻击基准：依赖投毒、SSRF、环境变量窃取、CSP 绕过、WebSocket 越权、超大产物、压缩炸弹和路径逃逸。

**验收出口**：真实容器/OS 环境下所有边界负测通过；Sandbox 不可用会拒绝任务；宿主凭证、网络和文件系统无隐式继承。

### E. 工程类型、模板、Recipe 与 OCP

**目标**：项目类型扩展是可组合能力，而不是新的中心化条件分支；模板速度与 Agent 灵活性各司其职。

- [x] ✅ Parser、Build/Runtime Validator、Template Planner、Bootstrap、Workspace Layout、Event Handler 等关键变化点已逐步 Adapter 化。
- [x] ✅ 模板初始化与生成需求等关键制品已强类型化，减少 Map/字符串协议漂移。
- [x] ✅ Vue、Backend、Full Stack 的模板/运行事实已接通；管理后台、移动端、后端多实体、全栈 CRUD Recipe 已补强。
- [x] ✅ CREATE 部分生成、缺少必需模板、无有效 Patch 等场景禁止误报成功。
- [ ] 给每个公开工程类型建立 Capability Manifest：支持的操作、route、模板、工具、验证下限、运行时、预览和 Benchmark。
- [ ] 为模板覆盖范围建立“能完整覆盖则 CREATE，否则在副作用前 HEAVY”的确定性判断；模板执行失败后是否可回退由工作区 mutation 事实决定。
- [ ] 每个 Recipe 必须有必需槽位、生成文件、契约、验证和失败语义，禁止只凭文件数量证明意图覆盖。
- [ ] Backend/Full Stack 增加真实数据库、迁移、API、鉴权、分页、错误合同和前后端字段一致性验收。
- [ ] 管理台/移动端增加真实浏览器交互、响应式、可访问性和关键业务流验收。
- [ ] 用 Branch Budget/Architecture Test 防止重新出现按 `CodeGenTypeEnum` 分散到多个服务的大型 `switch`。
- [ ] 在真实数据前暂停新增更多模板变体；先比较模板命中率、Fallback 率、一次成功率和单位交付成本。

**验收出口**：新增一种工程类型时，只新增声明和适配器；现有编排核心无需修改；每种公开能力都有真实生成与运行证据。

### F. 验证、发布、终态、快照与恢复

**目标**：工作区结果从执行到正式发布只有一条受 Fence 保护、可对账、可恢复的路径。

- [x] ✅ 完成门禁要求意图、工作区结果和对应验证证据。
- [x] ✅ Lease、epoch、fence 贯穿执行、发布、恢复和终态。
- [x] ✅ Publication Journal、active pointer、回滚与 Reconciler 处理文件系统和数据库非原子窗口。
- [x] ✅ Terminal Intent/Effect Receipt 支持终态后事件、计费、清理等可重放副作用。
- [x] ✅ 回滚目录交换把激活作为唯一 commit point；结果未知时失效工作区并禁止盲重试。
- [x] ✅ **P0** 快照目录改为 `snapshotRoot/{appId}/{snapshotId}/manifest.json + payload/` 自包含 bundle，名称只作显示或索引，不作身份。
- [x] ✅ Manifest 绑定 `snapshotId/appId/kind/codeGenType/scope/taskId/executionEpoch/copyPolicy/treeHash/fileCount/byteCount/createdAt`。
- [x] ✅ RollbackPoint/Restore 制品升级到 v2 并携带 snapshotId、manifest hash、scope 和 executionEpoch；v1 可解析但禁止自动恢复和 Diff。
- [x] ✅ 快照先在 staging 构建 payload 与 Manifest、核对 bundle 指纹，再一次原子发布外层 UUID 目录。
- [ ] 复制与恢复严格拒绝不支持的文件类型和路径逃逸；Windows 跳过的 symlink 测试必须在支持环境补证。
- [x] ✅ SnapshotRollbackTool 保留调用方相对 scope；Diff、手工回滚、自动回滚、Checkpoint 和 Catalog 全部迁移到同一读取服务。
- [x] ✅ 缺失、损坏、篡改、旧格式、身份不一致、树摘要不一致均在移动目标目录前 fail-closed。
- [ ] 建立故障注入矩阵：模型返回前后、工具写中、验证中、publish prepare/move/pointer/DB commit、terminal outbox、Redis、清理、进程 kill。
- [ ] 每个故障点验证 task/app/workspace/pointer/credit/event/quality sample 的最终一致性和可重放性。

**验收出口**：任何恢复都能证明“恢复谁、恢复到哪里、内容是什么、由哪个任务创建”；故障注入后不存在双 active、跨 scope 覆盖、永久 RUNNING 或重复计费。

### G. 用户体验、持久回执与可观测性

**目标**：用户始终知道系统正在做什么、还要多久、结果是否可信、失败后能做什么。

- [x] ✅ 用户阶段已与内部 Agent 节点解耦，支持理解、规划、实现、预览、验证、审批、交付等稳定语义。
- [x] ✅ ETA 基于历史 route/阶段分位数并携带 confidence 与 deadline risk。
- [x] ✅ 暂定预览已提升为任务级所有权，验证返回后不立即停止；发布/终态按 workspace/fence 清理。
- [x] ✅ SSE 支持序列、重放、显式 gap 与 durable terminal，跨实例查询有持久回退。
- [x] ✅ **P1** 新增 durable delivery receipt：实际 route、变更摘要、验证摘要、预览成熟度、失败分类、可恢复性、下一步和成本摘要。
- [x] ✅ **P1** `GenerationTaskStatusVO` 增加结构化 `failureCategory/retryable/recoveryAction/validationSummary/deliveryReceipt/costSummary`，以版本化方式兼容旧客户端。
- [x] ✅ 实时终态、Redis 重放、数据库回退必须来自同一投影；刷新后信息不能从具体错误降级成“项目生成失败”。
- [x] ✅ 审批等待、被拒、过期、取消、Deadline、Provider 暂时失败、工作区结果未知分别提供可行动中文提示。
- [ ] 真实浏览器验收暂定/已验证预览：首次可用时间、并发隔离、刷新、发布切换、失败保留和资源释放。
- [ ] 建立用户体验 SLO：首个有意义响应、首个可用预览、总耗时、取消生效、失败可解释、恢复完成和资源释放。
- [ ] 公开事件只暴露稳定、低敏字段；内部异常、路径、模型凭证、租户标签和高基数诊断留在受控日志/Trace。

**验收出口**：用户刷新或换实例仍能得到与实时链一致的结果说明；每个失败都有稳定分类和下一步；预览与进度指标可按场景追踪。

### H. 成本、容量、鉴权与应用治理

**目标**：在模型调用前做可解释准入，在终态后做账实一致结算，平台和租户都能控制风险。

- [x] ✅ 任务幂等、重复生成拦截、应用所有权、匿名入口限流和登录后 session 轮换已建立基础。
- [x] ✅ 用户/租户/Heavy 并发、月预算、预检上限和积分预授权在模型调用前参与准入。
- [x] ✅ Provider 尝试账本、用户计费和单位成功交付成本已分层建模。
- [x] ✅ 应用生成中删除、版本化制品和端口清理已有门禁与清理路径。
- [x] ✅ **P1** 提交回执展示预计积分区间/最大冻结额；运行中展示预算消耗；终态展示实际扣费、退还或免除原因。
- [ ] **P1** 建立租户管理员只读控制面：月预算、剩余额度、排队、按场景单位成功成本和拒绝原因；普通成员不得查看全租户成本。
- [ ] 对任务恢复、Provider 重试、取消、Deadline、发布后终态恢复建立账实一致测试，重复扣费为 0。
- [ ] 在跨实例压测中证明租户公平、无饥饿、锁顺序稳定和额度不超发；指标禁止直接使用 tenantId 高基数标签。
- [ ] 建立应用级控制：暂停生成、最大并发、模型策略、网络/依赖权限、预算上限、危险工具策略和紧急 kill switch。
- [ ] 建立控制面 RBAC/所有权矩阵，覆盖提交、查询、取消、审批、恢复、终态重放、Benchmark、模型配置和应用删除。
- [ ] 形成威胁模型与审计事件：谁在何时对哪个 app/task 执行了什么受控操作、结果如何；日志必须脱敏且可保留/删除。
- [ ] 模型 failover、hedge、降级和路由学习必须同时通过质量、尾延迟、Provider 成本、用户积分和容量门禁。

**验收出口**：无横向越权、无重复扣费、无预算超发；用户能解释单任务费用，管理员能控制租户和应用，策略变更可审计和回滚。

### I. Benchmark、学习闭环、性能与代码健康

**目标**：先用数据判断质量、速度和成本，再决定是否调整 Prompt、模型、路由或 Agent 结构。

- [x] ✅ 已有声明式 Dataset、结构/安全规则、浏览器与 Backend Runtime Grader、报告校验和 Release Gate。
- [x] ✅ Benchmark Evidence 绑定候选身份、模型/Prompt/数据集指纹并支持 provenance 校验。
- [x] ✅ 策略晋级已纳入结果质量、尾延迟和单位成功成本。
- [x] ✅ NO_PLAN/COMPACT_PLAN/CURRENT_DAG 消融基础设施已存在。
- [x] ✅ E2 **P0** 扩充能力矩阵数据：CREATE、READ_ONLY、LIGHT、AGENT、HEAVY，以及 HTML/MULTI_FILE 的支持合同。
- [x] ✅ 每个已声明的 route × 工程类型矩阵单元至少 3 个确定性 Fixture；高风险路由已有至少 10 个中文表达变体与负例。
- [x] ✅ 空项目 PLAN、仓库 AUDIT、提示注入、秘密文件、部分读取与 Vue→Full Stack 跨类型升级已有确定性样本。
- [x] ✅ Fallback 样本声明 `REQUIRED / FORBIDDEN / OPTIONAL` 三态期望，并由持久任务命令恢复目标类型与回退原因后判定报告。
- [x] ✅ 取消故障样本已绑定 `taskId + executionEpoch`、取消原因和实际 JUnit 测试方法，覆盖排队激活隔离与心跳取消传播。
- [x] ✅ 审批故障样本已绑定 `taskId + executionEpoch` 和实际 JUnit 测试方法，覆盖审批重排队的旧 fence 隔离与 continuation 投递失败后的等待态恢复。
- [x] ✅ 工具审批记录以 `taskId + requestExecutionEpoch + toolName + argumentsDigest` 作为持久执行身份；审批、拒绝、执行、结果提交和恢复均按同一请求纪元更新，无法证明纪元的历史记录禁止执行。
- [x] ✅ 恢复故障样本已绑定 `taskId + executionEpoch` 和实际 JUnit 测试方法，覆盖跨 epoch 检查点隔离与已消费工具 receipt 的幂等恢复。
- [x] ✅ 发布故障样本已绑定 `taskId + executionEpoch` 和实际 JUnit 测试方法，覆盖 active pointer 回滚失败与文件系统已激活 crash window 的安全前滚。
- [ ] 运行真实模型、真实浏览器、Backend/Full Stack，并保留候选、数据、环境和报告身份；Mock 结果不进入晋级。
- [ ] 运行规划层三组同源消融，在质量不降的前提下比较准备耗时、总耗时、Token、工具轮次和成功成本；报告前暂停删除/增加节点。
- [ ] 建立离线与在线关联：Benchmark 维度必须能解释生产失败、返工和低评分，否则删除无价值指标。
- [ ] 所有新策略先 shadow/canary，门禁通过后小流量晋级；保留自动回滚阈值和旧 release identity。
- [ ] 性能分析按端到端关键路径进行：预检、排队、上下文、首 Token、工具、构建、预览、发布、终态；禁止只优化局部方法耗时。
- [ ] 代码健康门禁：重复事实源、跨层依赖、中心类分支预算、未使用兼容层、异常吞噬、无界缓存、低信息注释和高圈复杂度。
- [ ] 每轮重构必须有“删除了什么”的结果；只新增抽象而未移除旧路径不能宣称降债。

**验收出口**：每项策略决策都有可重复报告；有效交付率、尾延迟和成功成本共同决定晋级；架构复杂度随能力增长近似线性，而非分支爆炸。

### J. P2 兼容与浅层代码债（当前冻结）

这些问题是真实冗余，但不会先于 P0；只有在恢复兼容与回放证据齐全后再删除。

- [ ] 删除已经退役、只返回原请求的 `IntentClarificationStage` 及其浅测试，同时让 Executor 直接消费提交阶段冻结的 Scenario Decision。
- [ ] 把 24 字段、11 个 schema 版本兼容责任从 `GenerationTaskCommand` 主记录迁入版本化 Codec/Factory；保留旧 Decoder，直到历史任务回放样本全部通过。
- [ ] 删除 Pipeline 中仅作字符串拼接或旧签名桥接、且没有独立不变量的兼容层；先用调用图和恢复样本证明可删。
- [ ] 清理模板化、乱码和低信息注释；注释只保留约束、原因、失败语义和非显然权衡。
- [ ] 只有“删除测试”证明复杂性已经外溢时，才继续拆分 FileSystem、Runtime 或 Finalizer；禁止按文件行数机械拆类。
- [ ] 每次债务清理必须同时减少旧路径、构造器、分支或重复事实；只新增 Facade/Interface 不算降债。

## 9. 场景矩阵：每种请求应如何处理

下表是目标合同，不是对当前代码全部已实现的声明。

| 场景 | 目标 route/处理 | 允许副作用 | 最低证据/验证 | 用户体验合同 |
|---|---|---|---|---|
| 空项目 PLAN/技术方案 | READ_ONLY | 不写项目、不预配 DB、不构建 | 需求/冻结规格依据；仓库引用可为空 | 返回计划和假设，不因无文件失败 |
| 已有项目 EXPLAIN | READ_ONLY | 工作区字节零变化 | 已采集文件/符号引用 | 可定位说明；证据不足明确告知 |
| 已有项目 AUDIT | READ_ONLY | 工作区字节零变化 | 风险项必须绑定仓库事实 | 不编造路径；按严重度给改造边界 |
| Vue/Backend/Full Stack 简单首次创建 | CREATE（能力支持时） | 受 Fence 的模板/Recipe 写入 | 必需槽位 + observed build/runtime | 尽快给暂定预览，验证后升级成熟度 |
| HTML/MULTI_FILE 简单首次创建 | 当前应能力协商到 HEAVY 或零计费拒绝 | 不得在 Admission 才意外失败 | 对应路线的真实验证 | API 可选项与后端能力一致 |
| 高复杂度/基础设施首次创建 | HEAVY_EXPERT | 受控写、危险工具按策略审批 | 架构/构建/运行时/安全证据 | 明确耗时、成本上限和阶段 |
| 文案/颜色/单文件低风险编辑 | LIGHT_EDIT | 小范围受控 Patch | 实际 FAST；风险升高则 BUILD | 快速完成；Fallback 原因可见 |
| 多文件业务逻辑编辑 | AGENT_EDIT | 顺序写工具、只读工具可并发 | observed BUILD，必要时 RUNTIME | 展示修改摘要和验证结果 |
| 跨前后端/数据库/高破坏性改造 | HEAVY_EXPERT | 高风险操作需能力或审批 | EXPERT + Full Stack/DB 契约 | 提前说明影响范围与安全检查点 |
| 已有项目构建失败后的修复 | 原 route 内受限 Repair 或 HEAVY | 仅可信工作区、限定修复预算 | 修复前后诊断与 observed validation | 说明修复轮次；预算耗尽明确失败 |
| 新建项目首轮空产出 | 失败关闭 | 不进入无依据“自动修复” | 空产出事实 | 不假装正在修复；给可行动原因 |
| 低置信歧义请求 | 有界 Preflight 澄清一次 | 无工具、无写入、无构建 | 澄清结果与超时/降级事实 | 可取消；不无限追问 |
| 网络/依赖安装 | 原 route + Capability/Approval | 仅 allowlist 网络与最小环境 | Trust 校验、命令/依赖 receipt | 明确为何需要权限及影响 |
| 破坏性删除/恢复覆盖 | HEAVY/Approval | 审批后受 Fence 执行 | 参数摘要、快照身份、恢复结果 | 可拒绝；未知结果禁止一键重试 |
| WAITING_APPROVAL 后刷新/重启 | 同 task/epoch Continuation | 未批准前无受控副作用 | 持久审批与 continuation receipt | 状态可恢复，批准/拒绝幂等 |
| 用户取消 | 终止当前 route | 取消后不得新增副作用或发布 | 取消传播与资源释放事实 | 快速确认；说明是否已扣费/退还 |
| Deadline/Provider 超时 | 明确 timed_out/failed | 仅安全清理和账本收口 | 调用结果、终态、结算 | 与用户取消区分，给重试策略 |
| Worker 崩溃/租约过期 | Recovery Policy | 旧 epoch 禁止继续写 | lease/fence/检查点/发布日志 | 自动恢复或明确不可安全恢复 |
| 同一应用并发写任务 | 准入串行或明确拒绝 | 不创建第二个写执行 | app/task ownership | 快速拒绝，不冻结重复费用 |
| 客户端重复提交 | Idempotent replay | 只产生一个任务/预授权 | idempotency receipt | 返回同一 taskId 与状态 |
| 刷新、断线、跨实例查询 | Durable status/SSE replay | 只读查询 | sequence/gap/terminal projection | 不丢终态；gap 显式 |
| 发布时 DB/Redis/进程故障 | Reconcile/Terminal Effect replay | 仅幂等补偿 | journal、pointer、terminal intent | 最终状态一致，不重复收费 |
| 手工/自动快照恢复 | Manifest-bound restore | 校验全部身份后原子替换 | manifest hash、tree hash、scope | 不匹配直接拒绝；未知结果人工核对 |

## 10. 测试与发布门禁

### 10.1 每个改造包的 Definition of Done

- [ ] 问题能够由失败测试或可重复诊断先证明，而非只凭代码观感。
- [ ] 权威事实所有者、调用者、持久边界和失败语义在代码/ADR 中明确。
- [ ] 正常、边界、取消、超时、并发、恢复、越权和旧版本兼容场景有对应证据。
- [ ] Targeted test 通过，并记录数量、跳过项和跳过原因。
- [ ] 相关模块测试、架构测试、`git diff --check` 通过。
- [ ] Full suite 的结果单独记录；若被既有问题阻塞，必须明确阻塞与本改造的边界。
- [ ] 涉及真实模型、浏览器、Backend、容器或 Redis 的项目完成 E3/E4，不能用 Mock 替代。
- [ ] 涉及生产策略的项目完成 shadow/canary、告警和回滚演练后才达到 E5。
- [ ] 独立中文提交，只包含本改造包；工作树其他改动不得被顺带提交。
- [ ] 清单状态、Commit、测试、Benchmark、已知限制和下一步同步更新。

### 10.2 必做故障矩阵

| 故障点 | 必须证明的结果 | 当前证据 |
|---|---|---|
| Preflight 模型超时/取消 | 不创建独立收费任务；局部超时的预授权由主任务接管，明确取消结算预检额度并中止提交 | ✅ E2 `3035dc4` |
| 队列重复投递/Worker 重启 | 只有一个 epoch 获得副作用权限 | ✅ E2；Redis 跨实例 E3 待环境 |
| 工具部分成功/返回丢失 | 已成功项可追踪，不重复不可逆操作 | ✅ E2 |
| 构建阻塞/孙进程残留 | Deadline/取消可终止进程树并释放资源 | ✅ E2 / 🟡 真实构建 E3 |
| 发布 candidate 移动前后 | active pointer、journal、正式目录最终一致 | ✅ E2 / 🟡 kill/磁盘 E3 |
| 发布后 DB 终态失败 | Reconciler/Terminal Intent 最终补齐，不误报失败 | ✅ E2 / 🟡 真实 DB E3 |
| Redis 中断/SSE 重连 | gap 显式，数据库终态可回退，不重复终态 | ✅ E2；真实 Redis 跨客户端 E3 待环境 |
| 计费结算前后崩溃 | 幂等结算，余额、流水、任务一致 | ✅ E2 / 🟡 真实 DB E3 |
| 快照 Manifest/树内容篡改 | 恢复前拒绝，目标目录保持不变 | ✅ E2 / 🟡 跨平台 E3 |
| 回滚激活结果未知 | workspace invalidated，禁止自动重试 | ✅ E2 / 🟡 真实文件系统 E3 |

本地 E2 统一命令：`.\mvnw.cmd -Pgeneration-failure-matrix test`。需要 Redis 的 E3 用例同时带 `integration-test` profile，并显式提供 `integration.redis.host` 与 `integration.redis.port`；缺少真实环境时不得记为通过。

## 11. 当前工作树 WIP 台账（不计入已完成）

当前工作树包含多组并行改动，必须按以下口径处理：

| WIP | 方向判断 | 当前证据 | 尚缺什么 |
|---|---|---|---|
| Execution preview 状态扩展 | 所有权未核清 | `GenerationExecutionContext/Snapshot/ToolContinuationState` 仍有工作树修改 | 单独核对目标、测试与提交，不计入 P0-1 至 P0-4 |
| Execution workspace 清理 | 所有权未核清 | `GenerationExecutionWorkspaceService` 及测试仍有工作树修改 | 单独审计生命周期语义，不与已提交 Snapshot 改造混合 |
| Terminal Effect SQL/Architecture Test | 所有权未核清 | SQL schema 与新 Architecture Test 仍在工作树 | 按终态副作用主题独立验证、独立提交 |

### 11.1 本轮交付证据边界

- 2026-08-27 完成 P0-1 至 P0-4 的代码闭环，共 9 个独立中文提交：`31cf6fc`、`2835f96`、`e31082e`、`cac06c0`、`fc86efc`、`1abd79b`、`c338e14`、`2874c2e`、`3ace480`。
- 在 `3ace480` 的独立 detached worktree 上使用 JDK 21 执行 `.\mvnw.cmd test`：3419 tests、0 failure、0 error、42 skipped；该结果不包含当前工作树的并行 WIP。
- 当前工作树组合回归为 3423 tests、0 failure、0 error、42 skipped，只用于发现文件交互问题，不替代独立提交态证据。
- 当前 Windows 环境有 3 项快照 symlink 测试因权限跳过；P0-4 的跨平台 E3 证据仍未完成，不能把代码测试写成真实文件系统验收。
- 未恢复或修改已暂存删除的旧蓝图；未提交 SQL、Execution preview、Execution workspace 等所有权未核清的并行 WIP。

### 11.2 P0-5 能力矩阵交付证据

- 2026-08-28 提交 `783e5dd`、`92e0b4f`、`43d4d25`、`1bc5ef7`：v3.3 Dataset 从 33 条扩充为 58 条，route 分布为 CREATE 12、READ_ONLY 12、LIGHT_EDIT 12、AGENT_EDIT 12、HEAVY_EXPERT 10。
- 数据集任务本身声明受支持的 route × 工程类型单元；Catalog 拒绝少于 3 条 Fixture 的既有或新增单元，扩展能力不需要新增 Java 路由分支。
- READ_ONLY 对 EXPLAIN、AUDIT、空项目 PLAN 各提供 3 条确定性样本；HTML 与 MULTI_FILE 各提供 3 条 HEAVY 首次生成支持合同。
- 只读 Benchmark 独立评估最终响应合同与执行前后工作区摘要；成功终态不解析发布目录，也不运行 Runtime/Visual Grader。
- 提示注入、秘密文件和跨前后端部分读取样本声明了最终响应必含/任选/禁含字符串；这些断言与 Fixture 一并进入数据集指纹。既有敏感文件允许被只读审计，新增或改写敏感文件仍由安全评分器拒绝。
- 使用 JDK 21 执行整个 `orchestration/benchmark` 测试包：173 tests、0 failure、0 error、0 skipped；该结果仅属于 E2，未运行真实模型、真实浏览器或容器故障基线。
- 本轮未调整 Prompt、模型池、规划 DAG 或路由阈值；历史 `buildPassRate` 字段仍是兼容报告口径，READ_ONLY 的有效性由 FUNCTIONAL/DIFF_SCOPE/SECURITY 证据评估，不表示执行了构建。
- `3035dc4` 修复了显式取消被 Preflight 局部降级吞掉的问题；`626bada` 建立统一故障矩阵入口，`a5714d4` 将快照篡改场景纳入矩阵，本地 E2 执行 104 tests、0 failure、0 error、0 skipped。
- 在干净的 detached `f431dac` 工作树上使用 JDK 21 执行默认 `./mvnw.cmd test`：774 reports、3430 tests、0 failure、0 error、42 skipped；该结果不包含主工作树并行 WIP，也不包含默认关闭的 `generation-browser-smoke` 集成 profile。
- 2026-08-28 提交 `d33f6eb`：Dataset 升级到 v3.4、59 条，新增 Vue→Full Stack 升级样本；Benchmark 以持久任务命令中的目标工程类型解析发布目录，以 `appId + source type + target type` 作为逻辑工作区身份，允许同一应用的版本化物理目录变化，同时拒绝跨应用或意外类型比较。
- 跨类型升级使用独立 DIFF_SCOPE 规则验证源契约保留和目标布局产出，不复用同类型小范围编辑的文件数量约束；旧数据未声明 `sourceCodeGenType` 时默认与目标类型一致，保持向后兼容。
- 使用 JDK 21 执行 Benchmark 与相关架构测试：45 reports、185 tests、0 failure、0 error、0 skipped；统一故障矩阵仍为 104 tests、0 failure、0 error、0 skipped。
- 在干净的 detached `d33f6eb` 工作树上使用 JDK 21 执行默认 `.\mvnw.cmd test`：777 reports、3439 tests、0 failure、0 error、42 skipped；该结果不包含主工作树并行 WIP，也不包含默认关闭的 `generation-browser-smoke` 集成 profile。
- 上述结果只证明确定性 E2 评测事实链可判定；本轮未修改生产执行工作区的跨类型源目录种子语义，因此不能据此宣称真实模型迁移成功或源工程内容已在生产链路保留，仍需在 E4 样本中验证。
- 2026-08-28 提交 `e45ba95`：Dataset 升级到 v3.5，6 条 HTML/MULTI_FILE 样本要求观测能力协商回退，1 条 Vue CREATE 样本禁止回退；未声明旧样本默认 `OPTIONAL`，保持兼容。
- 报告校验拒绝缺失必需回退或出现禁用回退的任务结果；Fallback 期望进入 Dataset 指纹，旧候选报告不能绕过新合同。Benchmark 执行事实模块从持久任务命令读取 `taskId`、`appId`、目标类型和 `fallbackReason`，本地事件或遥测丢失时仍可跨实例恢复。
- 使用 JDK 21 执行 Benchmark、相关决策与架构测试：47 reports、200 tests、0 failure、0 error、0 skipped；统一故障矩阵为 15 reports、104 tests、0 failure、0 error、0 skipped。
- 在干净的 detached `e45ba95` 工作树上使用 JDK 21 执行默认 `.\mvnw.cmd test`：777 reports、3444 tests、0 failure、0 error、42 skipped；该结果不包含主工作树并行 WIP，也不包含默认关闭的 `generation-browser-smoke` 集成 profile。
- 上述 Fallback 结果仍是 E2 确定性合同与提交态回归证据，不等于真实模型下 HTML/MULTI_FILE 已完成 E4 生成质量验收。
- 2026-08-28 提交 `bd50736`：建立独立 E2 故障样本清单，不把预期取消混入生成质量 Dataset；首批两个取消样本分别绑定排队任务激活隔离和心跳取消传播，并要求 `taskId + executionEpoch`、取消原因及稳定断言事实。
- 使用 JDK 21 执行统一故障矩阵：16 reports、105 tests、0 failure、0 error、0 skipped；目录契约会反射校验样本对应的实际 `@Test`、故障证据注解和矩阵标签，避免清单与可执行测试漂移。
- 在干净的 detached `bd50736` 工作树上使用 JDK 21 执行默认 `.\mvnw.cmd test`：778 reports、3445 tests、0 failure、0 error、42 skipped；该结果不包含主工作树并行 WIP，也不包含默认关闭的 `generation-browser-smoke` 集成 profile。
- 上述取消结果仅证明单进程确定性 E2 行为与证据绑定，不等于真实 Redis、多 Worker 或实际长任务在取消后的副作用归零已完成 E3 验收。
- 2026-08-28 提交 `2163c88`：新增两条审批故障样本；审批重排队样本要求 epoch 提升且旧 fence 被拒绝，continuation 投递拒绝样本要求按 `taskId + executionEpoch + approvalId` 恢复等待态。
- 使用 JDK 21 执行统一故障矩阵：17 reports、110 tests、0 failure、0 error、0 skipped；在干净的 detached `2163c88` 工作树执行默认 `.\mvnw.cmd test`：778 reports、3446 tests、0 failure、0 error、42 skipped。
- 上述审批结果仅闭合 E2 故障样本与可执行行为绑定；审批记录自身绑定 task/epoch/tool/arguments digest、跨实例恢复和幂等决策仍属于未完成的生产合同，不能据此宣称审批链路已完成 E3 验收。
- 2026-08-28 提交 `bbe483d`：新增两条恢复故障样本；跨 epoch DAG 检查点样本禁止重排队和分发并请求安全终态化，已消费工具样本保留 replay receipt、跳过工具重置并恢复任务等待态。
- 使用 JDK 21 执行统一故障矩阵：18 reports、121 tests、0 failure、0 error、0 skipped；在干净的 detached `bbe483d` 工作树执行默认 `.\mvnw.cmd test`：778 reports、3446 tests、0 failure、0 error、42 skipped。
- 上述恢复结果仅证明 E2 内存替身下的确定性恢复策略；真实 Worker kill、数据库与 Redis 中断、跨实例调度和不可逆工具副作用仍需 E3 故障注入验收。
- 2026-08-28 提交 `f0b6645`：新增两条发布故障样本；active pointer 回滚失败时保留可前滚目录并记录 `ROLLBACK_REQUIRED`，文件系统已激活但 journal 未提交时复用同一 pointer、只补元数据且不重复移动目录。
- 使用 JDK 21 执行统一故障矩阵：18 reports、121 tests、0 failure、0 error、0 skipped；在干净的 detached `f0b6645` 工作树执行默认 `.\mvnw.cmd test`：778 reports、3446 tests、0 failure、0 error、42 skipped。
- 取消、审批、恢复和发布四类清单现均至少包含两条 E2 样本并绑定 `taskId + executionEpoch`、实际测试方法与稳定断言事实；这些结果仍不等于真实 DB/磁盘/进程故障窗口下的 E3 发布一致性验收。

### 11.3 P0-5 真实环境局部证据

- 2026-08-28 提交 `fc49b74`：新增 `generation-browser-smoke` profile，以部署方显式提供的 Chrome 与 ChromeDriver 启动真实 Selenium 会话，访问仅环回 HTTP 页面并采集 DOM、console、network 与 screenshot 证据。
- 本机 Chrome `151.0.7922.175`、ChromeDriver `151.0.7922.138` 执行 1 test、0 failure、0 error、0 skipped；该结果只证明浏览器探针 E3，不等于真实模型或完整生成任务 E3。
- Selenium 对 Chrome 151 输出“缺少匹配 typed CDP 模块”警告；本项目网络证据来自 performance log，本次断言确认 `captured=true`。本机仍缺 Docker、Redis 与 Go，相关 E3 未执行。
- 2026-08-28 在干净的 detached `418c69e` 工作树复跑 Chrome `151.0.7922.175` + ChromeDriver `151.0.7922.138`：1 report、1 test、0 failure、0 error、0 skipped；typed CDP 警告仍存在，DOM、console、performance-log network 与 screenshot 合同继续通过。
- 使用本机 MySQL Community Server `8.0.38` 和开发配置中已声明的数据源身份，在同一干净工作树执行 5 个专用 MySQL 集成类：5 reports、9 tests、0 failure、0 error、0 skipped；覆盖 41 条迁移校验、审批一次性执行与 replay receipt、Prompt 发布协调、语义记忆 outbox 和 Dev Server 会话并发/恢复合同。
- MySQL 集成仅重建 `ai_mother_dev_server_it`、`ai_mother_flyway_it`、`ai_mother_memory_it`、`ai_mother_prompt_release_it`、`ai_mother_tool_it` 五个专用库；取证后已删除并查询确认残留数为 0，业务库未进入测试目标。
- 当前环境探测：MySQL `3306` 可达、Node `22.16.0` 可用；Docker、Redis Server/CLI 和 Go 不可用，Redis `6379` 未监听，模型 API key 环境变量未配置。因此真实 Redis、多 Worker、Go Backend/Full Stack、真实模型质量与成本仍未形成 E3-E5 证据。
- 2026-08-28 提交 `d21080d`：`generation_tool_approval` 新增不可变 `requestExecutionEpoch`；新请求首次落库即携带工具名、参数摘要和 checkpoint，所有状态迁移均带请求纪元谓词，续跑查询只选择当前纪元或 `approval_dispatch_retry` 下最近一个可证明的旧请求。迁移只为仍处于 `waiting_approval` 的任务回填纪元，其他历史记录保持 `0` 并在执行/恢复路径失败关闭。
- 使用 JDK 21 执行审批、恢复、危险工具、续跑调度、持久映射和架构聚焦回归：78 tests、0 failure、0 error、0 skipped；在干净 detached `d21080d` 工作树执行默认 `.\mvnw.cmd test`：3448 tests、0 failure、0 error、42 skipped。
- 在同一干净提交态工作树使用 MySQL Community Server `8.0.38` 执行 `FlywaySchemaMigrationIntegrationTest` 与 `ToolApprovalMySqlIntegrationTest`：2 tests、0 failure、0 error、0 skipped；Flyway 校验 42 条迁移并从 baseline 后实际应用 30 条，最终到达 `20260828.1`，审批请求纪元、一次性执行和 replay receipt 完成真实数据库验证。`ai_mother_flyway_it` 与 `ai_mother_tool_it` 取证后已删除，残留查询为 0。
- 上述结果闭合审批持久身份和单 MySQL 实例 E3；真实 Redis、多 Worker 竞争领取与进程 kill 后的跨实例续跑仍未执行，不能据此宣称跨实例 E3 已完成。

### 11.4 P1 持久交付回执证据

- 2026-08-28 提交 `8e65d78`：新增独立 `delivery` 领域模型与工厂，交付回执只从实际 route、结构化结果质量和已观察完成证据生成；变更数量、验证级别、预览成熟度、失败分类、可恢复性、下一步和成本状态均为稳定低敏字段，不携带模型原文、文件路径或内部异常。
- 终态命令协议升级为版本 2 并冻结交付回执；解码、终态副作用和幂等撤销继续兼容版本 1，`V20260828_2__generation_delivery_receipt_intent.sql` 将数据库约束同步放宽到版本 1 至 2，避免代码协议领先于真实数据库约束。
- `GenerationTaskStatusVO` 合同版本升级为 2，新增字段均为增量字段；非终态保持空值，旧客户端可忽略未知 JSON 字段。版本 1 意图或迁移前终态由持久化结构化列补齐回执，不把内部 `errorMessage` 直接降级暴露到公开状态。
- 实时终态、Local/Redis 重放和数据库终态回退统一调用 `GenerationTerminalStreamEventFactory`；公开清理前先把 record 显式投影为白名单嵌套 Map，避免结构化摘要被字符串化。冻结命令中的成本先标记 `pending`，账本结算后数据库查询只向 `settled + actual tokens/credits` 升级。
- 使用 JDK 21 执行交付回执、终态命令、事件、状态查询、控制器和轻/重执行链聚焦回归：93 tests、0 failure、0 error、0 skipped；提交前补充的真实 MySQL 用例为 1 test、0 failure、0 error、0 skipped。
- MySQL Community Server `8.0.38` 实际校验 43 条迁移资源、从 baseline 后应用 31 条迁移并到达 `20260828.2`；版本 2 终态意图经真实 Mapper 写入后成功恢复失败分类、恢复动作、验证摘要和已结算成本。专用库 `ai_mother_delivery_receipt_it` 取证后已删除，残留查询为 0。
- 在干净 detached `8e65d78` 工作树执行默认 `.\mvnw.cmd test`：3458 tests、0 failure、0 error、42 skipped；该结果不包含主工作树并行 WIP，也不包含默认关闭的真实 Redis/模型/浏览器集成 profile。
- `8e65d78` 当轮只完成终态实际成本摘要；提交时预计积分区间、最大冻结额、运行中预算消耗、退还或免除原因随后由 11.6 所列独立提交闭合。真实 Redis 跨实例重放仍需 E3 验收。

### 11.5 P1 可行动中文指引证据

- 2026-08-28 提交 `f17ace0`：`GenerationTaskStatusVO` 合同升级为版本 3，并新增结构化 `guidance`；审批等待、审批决定已记录但续跑投递失败、取消处理中和终态失败均由同一 VO 投影返回稳定 `code/message/action/retryable`，不要求客户端解析内部 `stageMessage`。
- 审批请求、批准、拒绝和过期由用户体验映射器输出明确中文动作；批准、拒绝和过期决定在持久续跑实际激活后发布稳定 `eventId`，重复调度可按同一身份去重，事件发布失败不会阻断已持久化决定的恢复执行。拒绝事件从审批写服务迁出，避免写服务与续跑调度器重复发布同一事实。
- `GenerationFailureRecoveryAdvisor` 分别映射模型限流、模型超时、模型暂时不可用、取消和 Deadline；`GenerationErrorClassifier` 新增 `workspace_result_unknown`，识别目录交换、回滚恢复和物理结果未知标记，并强制 `retryable=false + reconcile_workspace`，中文提示明确要求核对文件与保留目录且禁止盲目重试或回滚。
- 使用 JDK 21 执行错误分类、交付回执、任务状态、SSE、审批服务与续跑调度聚焦回归：37 tests、0 failure、0 error、0 skipped；扩大到模型 failover、任务控制器、终态投影、持久查询、危险工具和 Heavy 执行相关回归：188 tests、0 failure、0 error、0 skipped。
- 在干净 detached `f17ace0` 工作树执行默认 `.\mvnw.cmd test`：3468 tests、0 failure、0 error、42 skipped；该结果不包含主工作树并行 WIP，也不包含默认关闭的真实 Redis、真实模型和浏览器集成 profile。
- 本轮完成的是 E2 合同、回归和提交态证据；真实 Redis 跨实例事件去重、多 Worker 审批恢复、真实 Provider 限流/超时响应以及工作区结果未知后的人工核对流程仍需 E3-E4 验收。

### 11.6 P1 成本可解释合同证据

- 2026-08-28 提交 `6dec4d0`：提交回执合同升级为版本 2，直接使用准入报价生成 `0..maximumReservedCredit` 的保守预计区间；幂等重放通过原任务的预授权流水恢复相同冻结上限，不按可能漂移的新配置重新报价。
- 运行中成本由独立投影服务组合预授权流水、Provider 物理调用账本和可替换计费策略；只用已完成 attempt 计算 `provisionalCreditCost`，未完成 attempt 保持待结算，不解析流水备注，也不把未知成本解释为零。
- 终态以任务结算字段和结构化结算流水交叉核对实际扣费、计费 Token 与退还额；Provider 超时、失败或组合故障分别返回稳定免除原因。任务、租户、用户、Token 或结算差额不一致时成本投影失败关闭，但任务状态查询仍可用且不会暴露内部异常。
- 任务状态、交付回执和数据库终态 SSE 回退共享扩展后的成本摘要；公开字段包括最大冻结额、Provider 已观察 Token、运行中暂估、实际扣费、退还额/原因和免除 Token/原因，实时终态在账本尚未结算时继续明确返回 `pending`。
- 使用 JDK 21 执行成本、准入、幂等、状态、控制器、终态事件和积分结算相关回归：164 tests、0 failure、0 error、0 skipped；随后新增账本冲突 fail-closed 与成本投影故障不遮蔽任务状态测试，2 项均进入提交态默认全量回归。
- 使用本机 MySQL Community Server `8.0.38` 执行 `GenerationDeliveryReceiptMySqlIntegrationTest`：1 test、0 failure、0 error、0 skipped；实际校验 43 条迁移资源并从 baseline 后应用 31 条到 `20260828.2`，验证冻结 5、运行中暂估 2、Provider 超时免除 20000 Token、终态实际扣 2 和退还 3。专用库 `ai_mother_delivery_receipt_it` 在测试结束后删除。
- 在干净 detached `6dec4d0` 工作树执行默认 `.\mvnw.cmd test`：3475 tests、0 failure、0 error、42 skipped；该结果不包含主工作树并行 WIP，也不包含默认关闭的真实 Redis、真实模型和浏览器集成 profile。
- 本轮闭合单任务成本解释的 E2 与单 MySQL 实例 E3；真实 Redis、多 Worker、Provider 实际响应、租户控制面、额度超发和重复扣费故障注入仍按第 372 至 374 项继续验收。

## 12. 推荐执行顺序

### 第一阶段：事实正确性

1. P0-1：READ_ONLY 零写 + 三种只读证据合同 + Repository Context Trust。
2. P0-2：Observed Validation Evidence + Fallback effective request。
3. P0-3：Registry 驱动的 Capability Negotiation，删除重复 Catalog。
4. P0-4：Snapshot Manifest/Scope/Tree Hash 和所有消费者迁移。

完成条件：所有成功、失败、路由、恢复和快照身份都只有一个事实源。

### 第二阶段：可恢复与可解释

1. Terminal failure taxonomy 与 durable delivery receipt。
2. 用户成本上限、实际扣费、退还与重试策略。
3. Publication/Terminal/Snapshot/计费故障矩阵。
4. 真实浏览器、跨实例 SSE、取消和资源释放验收。

完成条件：用户刷新后仍能理解结果，平台可在任何中断窗口恢复或明确人工处置。

### 第三阶段：用数据决定 Agent 工程

1. 补齐场景/route/工程类型 Benchmark 矩阵。
2. 运行真实模型、浏览器、Backend 与 Full Stack 基线。
3. 运行规划层消融、Context Retrieval 性能和路由 shadow。
4. 只有报告同时满足质量、尾延迟和单位成功成本，才晋级策略。

完成条件：每个 Agent、规划节点、模型和路由规则都有可量化收益；没有收益的复杂度被删除。

### 第四阶段：生产运营

1. 租户/应用控制面、审计和 RBAC 全矩阵。
2. 多租户公平、容量、账实一致与攻击演练。
3. Canary、告警、自动回滚和定期恢复演练。

完成条件：链路从“工程可运行”升级为“生产可治理”。

## 13. 对 Codex / Claude Code 的借鉴边界

借鉴的是机制，不是表面形态：

- Codex 的任务隔离、Sandbox、跨边界审批、网络策略、凭证隔离和 Agent 可观测性，适合转化为本项目的执行 Capability 与审计合同。
- Claude Code 的权限层与 OS Sandbox 叠加、子进程继承限制、确定性 Hooks 和有界子 Agent，适合转化为本项目的 fail-closed 策略和生命周期门禁。
- 子 Agent 只用于边界清晰、上下文隔离有收益的任务；需要共享大量上下文或严格顺序时保留单一主执行者。
- Hooks/Policy 用于确定性安全和验证，模型用于需要判断的规划；不能让另一个 Agent 代替硬性安全门禁。

官方参考：

- [OpenAI：Running Codex safely](https://openai.com/index/running-codex-safely/)
- [OpenAI：Introducing Codex](https://openai.com/index/introducing-codex/)
- [OpenAI：Building the Codex Windows sandbox](https://openai.com/index/building-codex-windows-sandbox/)
- [Anthropic：Claude Code Security](https://code.claude.com/docs/en/security)
- [Anthropic：Permissions](https://code.claude.com/docs/en/permissions)
- [Anthropic：Sandboxing](https://code.claude.com/docs/en/sandboxing)
- [Anthropic：Hooks guide](https://code.claude.com/docs/en/hooks-guide)
- [Anthropic：Subagents](https://code.claude.com/docs/en/sub-agents)

## 14. 清单维护规则

1. 每个任务必须有稳定 ID、问题、用户影响、事实所有者、边界、依赖和验收出口。
2. 开工时改为 🚧；只有独立提交与相应证据齐全后才能改为 ✅/🟡。
3. 每个状态变更记录：`日期 / commit / tests / benchmark / evidence level / remaining risk`。
4. “已有测试文件”“过去某次全量通过”“Mock 可运行”不能作为当前验收结果。
5. 发现新问题先判断是否破坏五条 P0 不变量；若不是，进入对应领域 Backlog，不打断当前激活项。
6. 同一领域最多一个进行中改造包；文件所有权冲突时先合并/拆分，不并行覆盖。
7. 每个重构项必须记录删除的旧路径、重复事实或分支；只增加抽象不算完成。
8. 每次真实 Benchmark 固化 candidate、model、prompt、dataset、environment、report fingerprint，禁止选择性引用结果。
9. E5 前必须定义告警、回滚、人工处置和数据保留策略。
10. 本文是唯一执行总清单；ADR 解释单项决策，旧蓝图只保留历史背景，不再作为完成状态来源。

## 15. 相关项目文档

- [后端 AI 生成项目链路审计与优化蓝图](./后端AI生成项目链路审计与优化蓝图.md)
- [ADR：场景决策事实所有权](./ADR-场景决策事实所有权.md)
- [ADR：生成工作区信任模型](./ADR-生成工作区信任模型.md)
- [ADR：Agent 规划层消融协议](./ADR-Agent规划层消融协议.md)
- [暂定预览 Dev Server 所有权设计](./暂定预览DevServer所有权设计.md)

---

最后更新：2026-08-28。下一轮推进真实 Redis、多 Worker、模型、Backend、Full Stack 和跨平台 E3-E5 验收。
