# 后端 AI 生成项目链路审计与优化蓝图（三条主线版）

> 审计日期：2026-08-14
>
> 稳定基线：`dev@b4f05b2`
>
> 审计口径：已提交代码与当前未提交工作区分别判断；未提交修复不算生产能力。
>
> 核心目标：**生成结果正确可信、只读与写入场景体验匹配、交付更快、成本可解释、扩展不修改主干。**

---

## 1. 一句话结论

当前项目已经具备持久任务、租约与 fence、终态恢复、真实验证证据、模型调用账本、积分预授权、出站地址治理、公开视图隔离和容器沙箱等成熟工程基础，不属于“套壳 Agent”。

现在最值得投入的不是继续增加 Agent 角色，而是把仍然分散的三种事实收归三个 **deep module**：

1. **场景决策事实**：一个请求只理解一次，明确只读/写入、资源、路由、权限和验证下限；
2. **生成工作区信任事实**：任何模型生成的文件在安装、构建、运行前经过同一安全 policy；
3. **能力与质量事实**：工程类型、pipeline 能力和线上结果由注册式 adapter 与真实 fingerprint 统一表达。

蓝图只保留这三条主线。其余问题要么已修复，要么是这三条主线的派生症状。

---

## 2. 当前真实主链

```text
POST /generation/tasks
  -> GenerationTaskController.submit
  -> AppServiceImpl.submitGeneration
       -> 目前仍独立解析 Prompt，决定数据库资源
  -> GenerationTaskOrchestrator.start
       -> IntentProfileService.analyze
       -> GenerationRoutingDecisionEngine
  -> GenerationTaskSubmissionService
       -> 冻结 execution plan / SLA / 额度预授权 / admission
  -> GenerationTaskCommandExecutionService
       -> lease / fence / resource / execution workspace / session
  -> GenerationPipelineExecutor
       -> CREATE | LIGHT_EDIT | AGENT_EDIT | HEAVY_EXPERT
       -> 实际验证 evidence -> completion gate -> publish
       -> 数据库终态 -> terminal effects / SSE / credit settlement
```

真实任务入口是 `GenerationTaskController`；`AppGenerationController` 仅承担旧兼容入口。

---

## 3. 已完成能力：停止重复施工

下列高风险问题已进入 Git，应转为回归门禁，不再列为当前优化项目：

| 能力 | 状态 | 关键提交 |
|---|---|---|
| 任务终态与积分结算解耦 | 已落地 | `999e904` |
| evidence 只从实际验证 observation 派生 | 已落地 | `1254f0e` |
| 后端启动、端口、HTTP 健康验证 | 已落地 | `c778246` |
| Full Stack 后端与浏览器运行验证 | 已落地 | `763ff02` |
| DB 终态优先投影到 SSE | 已落地 | `7fed186` |
| terminal effect 分步骤 receipt 与重放 | 已落地 | `e53c14d` |
| 事件 replay 内存有界 | 已落地 | `0866201` |
| 物理模型调用先落 STARTED、可恢复 | 已落地 | `456e06c` |
| Provider 成本与用户收费策略分离 | 已落地 | `ea1b1f2` |
| 模型出站 SSRF/重定向/DNS 地址治理 | 已落地 | `6740847` |
| Public/Owner/Admin 视图与授权隔离 | 已落地 | `0c91544` |
| 意图关键词词界、否定识别、确定性优先级 | 已落地 | `b4f05b2` |

这些能力仍需要跨 module 回归测试保护，但继续围绕旧缺陷重构只会增加风险。

---

## 4. 当前仍成立的隐藏 Bug 与设计债

### 4.1 S0：生成项目的安装期控制文件仍可绕过 policy

当前未提交的 `ExecutableManifestPolicy` 只检查 `package.json`，`PnpmInstallCommandExecutor` 虽已在工作区加入 `--ignore-scripts`，但没有 `--ignore-pnpmfile`，也没有统一拒绝 `.pnpmfile.mjs/.cjs`、项目级 `.npmrc`、危险 `pnpm-workspace.yaml` 配置等控制文件。

pnpm 官方文档明确说明 `.pnpmfile.mjs/.cjs` 能挂钩安装过程，且 `ignorePnpmfile` 应与 `--ignore-scripts` 配合，才能确保安装时不执行脚本：

- [pnpm `.pnpmfile.mjs` hooks](https://pnpm.io/pnpmfile)
- [pnpm `ignorePnpmfile`](https://pnpm.io/pnpmfile#ignorepnpmfile)

此外，稳定分支 `b4f05b2` 仍以 `host-local` 为默认 sandbox，安装命令也尚未携带 `--ignore-scripts`；当前工作区已有向 container fail-closed 迁移的修改，但未提交、未验收，不能算生产修复。

**影响：** 模型生成内容可能在依赖安装阶段执行非预期 JavaScript、改变依赖解析或访问网络；这是 Agent prompt 无法可靠防住的执行 seam。

### 4.2 S1：EXPLAIN 被导入写链，AUDIT/PLAN 没有执行语义

- `IntentOperationType` 只有 `CREATE/EDIT/REPAIR/EXPLAIN`；
- `GenerationMode` 没有 `READ_ONLY`；
- `IntentProfileRoutingDecisionEngine` 对 EXPLAIN 最终选择 `AGENT_EDIT`；
- Agent 没有 patch 时会把正常只读结果当失败；
- 当前只读请求仍获得写工具、构建、修复和发布预算。

**用户表现：** “解释一下”“审计一下”“先给方案”会变慢、变贵，甚至因为没有代码修改而失败；更严重的是系统没有结构性保证“只读请求绝不写文件”。

### 4.3 S1：同一 Prompt 仍产生多份场景事实

`AppServiceImpl:102-104` 在主意图画像生成前调用 `AppDatabaseResourceServiceImpl.shouldEnableForPrompt`，而后者仍使用独立的裸子串规则；`IntentProfileService` 则使用另一套已版本化词法规则。

确定性冲突示例：`“新增用户字段”` 会在 `IntentProfile` 中命中 DATABASE，但资源预配规则不识别“字段”，因此决策认为需要数据库，实际命令却可能冻结为无需数据库。

Heavy、Agent 上下文与旧 routing policy 仍存在再次解释原始 Prompt 的入口。低置信度澄清又发生在 admission 和执行计划冻结后，只能 `replanModelProfile`，不能更正 route、资源、工具权限、SLA 或验证图。

现有 `GenerationScenarioDecisionSnapshot` 也不是决策真相：它在任务命令冻结后派生，只用于归因，并以手写常量 `intent-profile-v1/routing-policy-v1` 作为版本，无法承担执行授权与完整重放。

**影响：** 资源漏配或浪费、错误路由、错误上下文、无法解释的 fallback，以及“模型澄清正确但执行语义无法纠正”。

### 4.4 S1：能力目录重复，开闭原则没有兑现

当前生产代码中约 129 个文件引用 `CodeGenTypeEnum`，至少 43 个文件包含显式类型分支。CREATE 支持集合同时出现在未提交的 `GenerationExecutionCapabilityCatalog`、`CreateTemplatePlanner` 与 `SlotFillGenerationPipeline`；最终执行器仍要到 worker 内调用 `pipeline.supports` 才知道能否执行。

`IntentProfileRoutingPolicy` 对正常生产画像总会返回结果，因此 order 20–60 的五个旧 policy 在正常请求下不可达，却继续保留第二套关键词和第二套路由含义。

**影响：** 新增工程类型需要修改主路由、生成、解析、保存、构建、运行、发布、计费和上下文等多处；重复能力集合漂移后，错误只能在 worker 阶段暴露。

### 4.5 S2：注释数量高，但信息密度低

生产代码中约 160 个文件包含“先处理前置条件……”或“将可能失败的操作收敛……”等模板注释；大量 Javadoc 出现 `校验 ate`、机械拆词 `{@code Foo}{@code Bar}` 和乱码。

这些注释没有解释约束、失败语义或设计原因，反而增加 AI 与人的阅读噪声。大文件本身不是问题；真正的问题是事实所有权泄漏、重复分支和 shallow module。

---

## 5. 三条核心优化主线

## P0-A：Scenario Decision Kernel + 真正的只读执行

**目标：** 每个请求只产生一个不可变场景决策，后续 module 只能消费它，不能重新解析 Prompt。

决策至少覆盖：

```text
operation: CREATE | EDIT | REPAIR | EXPLAIN | AUDIT | PLAN
mutability: READ_ONLY | WRITE
targetType / affectedScopes / complexity / destructiveRisk
requiredResources
route / fallbackPolicy
toolPermissionProfile
validationFloor
contextHints
reason / confidence / ruleVersion / releaseFingerprint
```

实施边界：

1. 先生成 task identity 与有界 preflight budget，再做确定性分类；只有低置信度才调用模型澄清。
2. 澄清完成后一次性冻结完整决策，再进行资源预配、额度预授权与 execution plan；禁止运行期只改模型档位。
3. 增加 `READ_ONLY` 路径：写预算、构建预算、修复预算均为 0，不创建 patch、不发布 workspace，以带文件引用的 analysis artifact 和 `NO_CHANGE_JUSTIFICATION` 完成。
4. 数据库预配、路由、Heavy、Agent 上下文和验证计划只消费该决策；禁止各自持有 Prompt 关键词表。
5. 决策本体随任务命令持久化；归因 snapshot 从决策生成，不再反向拼装一个“看起来像决策”的 JSON。

验收门禁：

- EXPLAIN/AUDIT/PLAN 的 `toolWriteCount=0`、`buildCount=0`、`workspacePublished=false`；
- 同一 decision 可完整重放资源、route、权限和验证图；
- 低置信度澄清能够改变完整决策，且仍受 task/tenant 成本预算约束；
- 只读任务 P95 总耗时显著低于 AGENT_EDIT，模型调用默认不超过 1 次；
- 删除任一 Prompt 二次解析入口后，跨 module 测试仍通过。

**收益：** 直接提升只读场景体验与速度，减少错误资源和无效工具调用，并把“用户意图”集中到一个高 leverage 的 deep module。

## P0-B：Generated Workspace Trust Kernel

**目标：** 模型生成内容默认不可信；文件写入、依赖安装、构建、运行共享同一信任 policy 与 sandbox seam。

实施边界：

1. 将当前只检查 `package.json` 的 policy 扩展为生成工作区 policy，覆盖生命周期脚本、依赖协议、lockfile、`.pnpmfile.*`、`.npmrc`、`pnpm-workspace.yaml` 和其他可执行控制文件。
2. 依赖安装同时强制 `--ignore-scripts` 与 `--ignore-pnpmfile`；registry、代理、证书和网络目标由可信宿主配置注入，生成项目不得覆盖。
3. 生产启动必须显式选择 container sandbox；缺少 runtime、镜像、内部网络或资源限制时 fail-closed。`host-local` 仅允许 dev profile 显式开启。
4. 安装阶段仅开放依赖源 allowlist；构建与运行默认无外网；运行进程只接收最小环境，不继承平台密钥。
5. patch、Agent tool、自动修复和 CREATE 输出全部经过同一 policy；Benchmark 使用同一 implementation，而不是单独扫描后补判。

验收门禁：

- `.pnpmfile.mjs/.cjs`、项目级 registry 重定向、URL/git/file 依赖和生命周期脚本均有负向集成测试；
- 任一写入入口都不能绕过 policy；
- 生产配置缺失时应用拒绝启动，不能静默退回 host-local；
- sandbox 内看不到平台模型、数据库和租户密钥；
- 安装、构建、运行三种网络策略分别可审计。

**收益：** 消除最高风险的安装期代码执行 seam，同时保留 Agent 自主生成能力；安全限制集中后，业务 pipeline 不再重复堆黑名单。

## P1-C：Capability & Quality Kernel

**目标：** 新工程类型通过注册 adapter 扩展，pipeline registry 自己成为能力真相源；策略晋级由真实结果而不是常量版本驱动。

实施边界：

1. 每个工程类型 adapter 统一声明 generate/parse/save/build/runtime/publish/validate/cost/context 能力。
2. pipeline registry 导出 `(operation, mutability, type) -> capability` 矩阵；提交前即拒绝不可执行组合，删除重复 capability catalog。
3. 删除 order 20–60 不可达 policy 和重复关键词；保留一个生产决策流与一个显式 shadow challenger。
4. `releaseFingerprint` 由真实代码提交、配置、prompt、模型和 policy 指纹组成，禁止手写 `v1` 冒充发布身份。
5. 质量飞轮关联 decision、validation observation、repair、time-to-first-useful、time-to-delivered、provider cost 与用户反馈；只有质量、P95、成本和样本量同时达标才能晋级。
6. 清理模板注释和损坏 Javadoc，并增加静态门禁；注释只保留“为什么、约束、失败语义”。

验收门禁：

- 新增工程类型只新增 adapter 与测试，不修改主路由 switch；
- 任意 route/type 缺少 pipeline 时在提交前失败，而不是 worker 启动后失败；
- production 与 Benchmark 对同一 artifact 使用相同 verifier implementation；
- 任一策略版本可按真实 fingerprint 重放、比较和回滚；
- `CodeGenTypeEnum` 显式分支与不可达 policy 数量持续下降，并有架构测试防回升。

**收益：** 真正落实开闭原则，提高 locality；能力变化只修改一个 adapter，测试通过稳定接口覆盖，AI 也更容易准确导航代码库。

---

## 6. 实施顺序

| 阶段 | 交付 | 完成标准 |
|---|---|---|
| 0–48 小时 | 收口当前未提交 sandbox/manifest 修改；补 `ignorePnpmfile` 与控制文件 policy | 负向测试通过；生产 container fail-closed；形成独立 Git 提交 |
| 第 1 周 | READ_ONLY 垂直链：EXPLAIN/AUDIT/PLAN | 零写入、零发布、带引用 artifact；P95 明显优于 AGENT_EDIT |
| 第 2 周 | Scenario Decision Kernel 全链迁移 | 资源、route、权限、验证图只消费唯一决策；可持久化重放 |
| 第 3 周 | Capability & Quality Kernel | 新类型只注册 adapter；删除重复目录与死 policy；真实 fingerprint 可归因 |
| 持续治理 | 场景评测集与代码信息密度门禁 | 每次策略变更都有质量/速度/成本对比与自动回滚条件 |

优先级原则：**先封执行风险，再修用户能直接感知的只读场景，再做扩展性收口。**

---

## 7. 面向用户体验的核心指标

| 目标 | 核心指标 |
|---|---|
| 场景正确 | read-only 误写率、资源错配率、route fallback 率、clarification 改判率 |
| 生成质量 | 首次构建通过率、运行健康率、自动修复成功率、发布后回退率 |
| 生成效率 | time-to-first-useful、time-to-delivered、模型/工具调用数、上下文有效 token 比例 |
| 成本 | 每个 provider attempt 成本、任务预授权误差、失败/取消/超时成本、未结算账本年龄 |
| 可信交付 | validation observation 覆盖率、SSE 终态收敛率、sandbox policy 拒绝与绕过率 |
| 工程质量 | 新类型修改核心文件数、显式类型分支数、不可达 policy 数、损坏/模板注释数 |

任何策略晋级必须同时满足：质量不下降、P95 不恶化、成本在预算内、样本量足够、可自动回滚。不能只看路由命中率或单元测试数量。

---

## 8. 借鉴 Codex / Claude Code：借能力，不抄外形

值得借鉴的不是“多 Agent”命名，而是以下工程机制：

- 读、写、命令和网络权限是结构化策略，不依赖 Prompt 自觉；
- 默认在工作区与网络 sandbox 中执行，越权动作需要显式授权；
- 每个任务有隔离环境、可恢复状态、真实命令日志、diff 与测试证据；
- 对高风险动作 fail-closed，对低风险只读动作保持低摩擦；
- Agent 的声明不能替代编译器、测试器、浏览器和运行时 observation。

官方材料：

- [OpenAI：Running Codex safely at OpenAI](https://openai.com/index/running-codex-safely/)
- [OpenAI：Introducing Codex](https://openai.com/index/introducing-codex/)
- [Anthropic：Claude Code Security](https://code.claude.com/docs/en/security)

当前项目已经具备其中不少基础。下一步应把权限、sandbox、证据和决策做深，而不是增加重复读取同一上下文的角色。

---

## 9. 明确不做

- 不重写现有 Agent runtime；其 interface 相对小，先修输入决策和输出证据。
- 不拆微服务；先在单体内建立 deep module 和可靠事务 seam。
- 不让所有请求都先调用分类模型；确定性规则优先，低置信度才升级。
- 不按文件行数机械拆类；只按事实所有权、变更原因和失败语义抽 seam。
- 不继续新增只有名称差异的 Agent 角色。
- 不把当前未提交工作区当作已交付能力；每项必须独立提交、测试和可回滚。

---

## 10. 审计限制与交付口径

- 本轮是静态代码与 Git 历史审计，没有执行攻击载荷或生产环境渗透测试。
- 已在 JDK 21 对当前混合工作区执行全量 Maven 测试：`2836 tests / 0 failures / 0 errors / 41 skipped`；该结果证明当前代码可构建，不代表 S0 安装控制文件风险已经关闭。
- 仓库当前没有 `CONTEXT.md` 与 `docs/adr`；实施 P0-A/P0-B 时应补最小 ADR：场景事实所有权、只读语义、生成工作区信任模型。
- 当前工作区存在多组未提交实现；蓝图仅把它们视为候选修复，必须通过独立提交与干净工作树测试后才能更新状态。
- 蓝图只描述三条主线；具体 interface 形状应在对应主线启动时再做 design-it-twice，避免在问题尚未收敛时过早固化抽象。
