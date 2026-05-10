# AI 生成链路优化蓝图与实施状态

## 1. 目标

本轮优化的核心目标是：

- 让 AI 新建项目或改修项目更快
- 减少无效上下文，降低 token 消耗
- 提高改修场景的成功率和稳定性
- 为后续持续扩展（recipe、语义索引、workspace kernel）预留结构

本项目当前采用的策略是：

- 先在现有 Java 架构内完成高价值优化
- 优先验证“改修分流、上下文减重、轻量校验”是否成立
- 暂不直接跳到 Go workspace kernel 重构

## 2. 优化蓝图

### 2.1 总体方向

- Java 继续承担控制平面职责：应用管理、权限、SSE、任务调度、编排、前端 API
- 后续引入 Go workspace kernel，负责文件索引、补丁应用、快照、进程和增量校验
- 引入 SQLite / FTS5 / tree-sitter / ripgrep 形成更稳定的语义检索与工作区能力
- 让 AI 从“整页重写器”转向“变更规划器 + patch 生成器”

### 2.2 核心优化策略

- 意图分流：区分新建、改修、修 bug、加模块、样式调整等场景
- 上下文分层：只给模型必要上下文，不再整仓扫描
- Patch-first：优先输出变更计划、文件清单、最小 patch
- 分阶段验证：语法 / 类型 / 局部构建 / 全量构建逐级执行
- 模型分工：小模型做分类和检索，大模型只做关键 patch
- 事件化执行：规划、检索、编码、验证、回滚都通过结构化事件暴露

### 2.3 目标架构能力

- 轻量 / 重型编排链路分离
- Recipe 库（登录、权限、列表、Dashboard、数据库服务等）
- 语义索引与相关文件精选
- Git / Snapshot 一等公民
- 增量验证与增量补丁执行

## 3. 当前已实现部分

### 3.1 入口侧提示词优化与工作区拆分

- `POST /app/optimize/prompt` 已接入 `PromptOptimizerService`
- Chat 页面已拆分为 `ChatPageHeader`、`ChatMessagesPanel`、`ChatInputPanel`、`AppWorkspacePanel`、`SelectedElementAlert` 等组件，单页复杂度明显下降
- `BasicLayout` 在 chat 页面隐藏全局头部，让工作区更专注
- 输入区支持一键优化提示词，减少低质量 prompt 直接进入生成链路

### 3.2 生成入口拆分

- `AppServiceImpl` 已把生成准备流程拆为 `getGenerationApp(...)`、`enableDatabaseForGenerationIfNeeded(...)`、`prepareGeneration(...)`、`openGenerationSession(...)`、`startGenerationTask(...)`
- `prepareGeneration(...)` 内部再拆成 `recognizeGenerationIntent(...)`、`assembleGenerationContext(...)`、`routeGeneration(...)`、`buildGenerationPreparation(...)`
- 数据库资源会按提示词自动启用，减少手工准备
- 这层拆分把“意图识别 / 上下文装配 / 路由 / 编排准备”解耦了，后续接 recipe、索引和 `ChangePlan` 更顺

### 3.3 轻量 / 重型 DAG 分流

- `AgentGenerationOrchestrator` 已改成轻重链路分离
- 轻量链路：`planner -> context -> architect -> code -> review`
- 重型链路：`planner -> context -> architect -> code -> review -> buildfix`
- 重型链路仅在 Vue 新项目或明显需要 `build / compile / lint / test / package` 的场景触发
- 事件 payload 已携带 `orchestrationMode`

### 3.4 Patch-first 元数据贯穿链路

- `PlannerAgentNode`、`CodeAgentNode`、`ReviewAgentNode`、`BuildFixAgentNode` 都已支持结构化元数据
- 当前贯穿的关键字段包括 `patchFirst`、`requiresBuild`、`validationMode`、`generationMode`、`orchestrationMode`、`artifactMode`、`enabled`
- 改修场景不再默认按“全量重写 + 全量构建”处理
- `BuildFix` 只在确实需要构建校验时启用

### 3.5 上下文减重与意图化精选

- `GenerationAgentSupport` / `ContextAgentNode` 已落地精简上下文策略
- 意图分类覆盖 `auth / dashboard / management / analytics / settings / navigation / form / general`
- 只精选相关文件，不再整仓喂给模型
- 当前预算控制为：最多 6 个文件、单文件 1400 字符截断、总上下文 10000 字符
- 输出包含 `intent`、`selectedFiles`、`indexedFileCount`、`contextMode`、`projectContext`
- 已补 `GenerationAgentSupportTest` 覆盖文件精选和截断逻辑

### 3.6 Vue 构建验证分层

- `VueProjectBuilder` 已加入指纹化判断，依赖和源码未变化时可复用现有 `dist`
- 支持 `validate-light`、`build-light`、`light-done`、`reuse`、`done` 等分层结果
- 通过 `.ai-code-install.stamp`、`.ai-code-critical.stamp`、`.ai-code-presentation.stamp` 记录状态
- 构建结果新增 `validationTier`、`repairPriority`、`executionPath`，更利于后续自动修复和告警分流
- Vue 项目不再默认每轮都走完整 build

### 3.7 测试和构建入口修复

- `pom.xml` 已改成可显式控制 `skipTests`，不再把 surefire 永久硬跳过
- 定向测试现在可以正常执行并输出 reports
- 最新验证结果：
  - `.\mvnw.cmd -DskipTests compile`
  - `.\mvnw.cmd -Dtest=GenerationAgentSupportTest,CodeAgentNodeTest,ReviewAgentNodeTest,AgentGenerationOrchestratorTest,GeneratedProjectWorkspaceInspectorTest,GenerationRepairPolicyTest -Dmaven.resources.skip=true -DskipTests=false test`
  - `Tests run: 16`, `Failures: 0`, `Errors: 0`
- 全量 `mvn test` 仍包含若干外部依赖型集成测试，受 `PromptSafetyInputGuardrail` 长输入、`mmdc.cmd` 缺失和阿里云服务状态影响，与本轮优化代码无关

### 3.8 标准化 ChangePlan 与 prompt 压缩

- `CodeAgentNode` 已新增 `change_plan` 结构化 artifact，并把 `generation_spec` 收敛为更短的执行规范
- `ReviewAgentNode` 会在 `patch-first` 场景下检查 `change_plan` 是否存在，缺失则直接阻断后续生成
- `BuildFixAgentNode` 现在会消费 `change_plan` 里的 `rollbackStrategy` 和 `impactedModules`，让失败回退语义更明确
- `GenerationAgentSupport` 已补齐 `navigation / form` 等意图，且会过滤脏路径、忽略坏文件读取，不再把 `"null"` 塞进 prompt
- `AgentGenerationOrchestrator` 的 heavy path 估计与路由函数保持一致，并通过路由缓存避免同一 prompt 重复判路由
- `GeneratedProjectWorkspaceInspector` 现在忽略更多内部 stamp 文件，避免空工作区被误判成可自动修复

### 3.9 路由判定与门禁单点化

- 新增 `GenerationRoutingSupport`，`PlannerAgentNode` 与 `AgentGenerationOrchestrator` 共用同一套目标路由、build 门禁和 heavy path 判定
- build 关键词现在会直接驱动 heavy path 与 BuildFix 启用，避免“只进入重型 DAG、但门禁仍然关闭”的不一致
- `BaseGenerationAgentNode` 提供了共享的 artifact 读取辅助方法，`Code / Review / BuildFix` 三个节点去掉了重复默认值处理
- 新增 `GenerationRoutingSupportTest`，补齐路由与重型链路判定的回归校验

### 3.10 生成编排指标闭环第一阶段

- 新增 `GenerationOrchestrationMetricsCollector`，复用 Micrometer / Prometheus 体系记录生成编排指标
- `AgentGenerationOrchestrator` 已记录编排启动、成功、失败、质量门禁失败、总耗时、上下文规模、精选文件数、索引文件数、patch-first、BuildFix 启用、质量门禁结果和回滚策略计划
- `GenerationDagRunner` 已记录各 DAG 节点的执行耗时和失败耗时，指标按 `orchestration_mode`、`dag_node`、`stage`、`status` 区分
- `GenerationAgentContext` 现在携带轻 / 重链路模式，避免指标侧重复推断路由
- 新增 `AgentGenerationOrchestratorTest.shouldRecordOrchestrationMetrics`，覆盖核心指标落库到 `MeterRegistry`
- 最新验证结果：
  - `.\mvnw.cmd -Dtest=AgentGenerationOrchestratorTest,GenerationRoutingSupportTest,CodeAgentNodeTest,ReviewAgentNodeTest,GenerationAgentSupportTest -Dmaven.resources.skip=true -DskipTests=false test`
  - `Tests run: 13`, `Failures: 0`, `Errors: 0`

### 3.11 ChangePlan 契约校验闭环

- `ChangePlan` 已从简单 payload record 增强为标准契约对象，支持 `fromPayload(...)` 恢复、路径归一化、脏路径过滤、模块名去重和 payload round-trip
- `ChangePlan` 已集中校验 `schemaVersion`、文件变更范围、`validationLevel` 与生成规范一致性，以及构建校验场景的快照 / 稳定版本回滚策略
- `ReviewAgentNode` 现在不再只判断 `change_plan` 是否存在，而是会校验契约内容；无效的 patch-first 计划会被质量门禁阻断
- `BuildFixAgentNode` 现在通过标准 `ChangePlan` 消费 `rollbackStrategy`、`impactedModules` 和文件变更数量，避免重复解析松散 artifact
- `CodeAgentNode` 复用 `ChangePlan.normalizeFilePaths(...)`，让精选文件和变更计划使用同一套路径安全规则
- 修正了 `manual_retry_without_snapshot` 被误判为快照回滚策略的问题
- 新增 `ChangePlanTest`、`BuildFixAgentNodeTest`，并扩展 `ReviewAgentNodeTest` 覆盖无效 ChangePlan 阻断
- 最新验证结果：
  - `$env:JAVA_HOME='D:\java\jdk21'; $env:Path='D:\java\jdk21\bin;' + $env:Path; .\mvnw.cmd "-Dtest=ChangePlanTest,CodeAgentNodeTest,ReviewAgentNodeTest,BuildFixAgentNodeTest,AgentGenerationOrchestratorTest" "-Dmaven.resources.skip=true" "-DskipTests=false" test`
  - `Tests run: 12`, `Failures: 0`, `Errors: 0`

### 3.12 生成前本地回滚点

- 当前阶段不要求引入外部 Git 服务或远端代码托管组件；先复用本地工作区和 `CODE_SNAPSHOT_ROOT_DIR` 建立标准回滚点
- 新增 `RollbackPoint` 标准 artifact，统一输出 `provider/status/appId/taskId/snapshotName/snapshotPath/projectPath/sourceType/targetType/fileCount/reason`
- 新增 `GenerationRollbackPointService`，在进入真实代码生成前复制已有生成目录到本地快照目录，并忽略 `.git`、`node_modules`、`dist`、`target` 等非源码目录
- `AgentGenerationOrchestrator` 现在会在质量门禁通过后附加 `rollback_point` artifact；新建应用或缺失旧代码时会输出 `skipped` 状态和原因，不阻断生成
- 修正快照忽略规则为基于项目内相对路径判断，避免仓库上级路径包含 `target` 时误过滤整个项目
- 新增 `GenerationRollbackPointServiceTest`，并扩展 `AgentGenerationOrchestratorTest` 覆盖回滚点 artifact 输出
- 最新验证结果：
  - `$env:JAVA_HOME='D:\java\jdk21'; $env:Path='D:\java\jdk21\bin;' + $env:Path; .\mvnw.cmd "-Dtest=GenerationRollbackPointServiceTest,AgentGenerationOrchestratorTest,BuildFixAgentNodeTest,ReviewAgentNodeTest,ChangePlanTest" "-Dmaven.resources.skip=true" "-DskipTests=false" test`
  - `Tests run: 13`, `Failures: 0`, `Errors: 0`

### 3.13 失败事件携带回滚点元数据

- `AppServiceImpl` 已把生成失败、后台构建失败、构建校验失败、空项目失败等分支收敛到统一的错误事件 payload 构建逻辑
- `generation_error` 事件现在会在存在 `rollback_point` artifact 时附带完整回滚点 payload，前端和后续自动回滚逻辑无需再反查编排任务
- 当前仍保持非破坏性策略：只暴露可回滚元数据，不在失败分支自动覆盖项目文件
- 这一步补齐了“生成前快照已创建，但失败事件不可见”的链路缺口，为后续 opt-in 自动回滚和真实回滚率指标做准备

### 3.14 生成后本地 Diff 摘要

- 新增 `DiffSummary` 标准 artifact，输出 `status/appId/taskId/basePath/currentPath/addedCount/modifiedCount/deletedCount/addedFiles/modifiedFiles/deletedFiles/modifiedDetails`
- 新增 `GenerationDiffSummaryService`，基于生成前 `rollback_point` 对比当前项目目录，生成非破坏性的结构化 diff 摘要
- `AppServiceImpl` 现在会在最终生成成功时发送 `stage=diff` 的 `agent_event`，并把 `diff_summary` 写回本次 `GenerationPreparation.artifacts()`
- 后台构建链路已区分最终成功 / 失败，只有构建和自动修复最终成功时才发送最终 diff 摘要，避免失败后误报成功摘要
- `generation_error` payload 会在已有 `diff_summary` 时一并携带，便于前端或后续回滚逻辑定位“失败前最后一次已知变更”
- `DiffSummaryTool` 已改为复用同一套 diff 服务，避免工具侧和服务侧各维护一套差异判断逻辑
- 当前仍不执行补丁应用或自动回滚，只做可观测、可追踪的 diff 元数据闭环
- 新增 `GenerationDiffSummaryServiceTest` 覆盖新增 / 修改 / 删除统计和忽略目录逻辑
- 最新验证结果：
  - `$env:JAVA_HOME='D:\java\jdk21'; $env:Path='D:\java\jdk21\bin;' + $env:Path; .\mvnw.cmd "-Dtest=GenerationDiffSummaryServiceTest,GenerationRollbackPointServiceTest,AgentGenerationOrchestratorTest,BuildFixAgentNodeTest,ReviewAgentNodeTest,ChangePlanTest" "-Dmaven.resources.skip=true" "-DskipTests=false" test`
  - `Tests run: 15`, `Failures: 0`, `Errors: 0`

### 3.15 失败后 opt-in 本地快照回滚

- 仍不要求外部 Git 服务；本阶段只基于本地 `CODE_SNAPSHOT_ROOT_DIR` 执行受控恢复
- 新增 `RollbackRestore` 标准 artifact，输出 `status/appId/taskId/rollbackStrategy/snapshotPath/projectPath/backupPath/restoredFileCount/reason`
- 新增 `GenerationRollbackRestoreService`，失败后只有满足以下条件才自动覆盖恢复：
  - `change_plan.rollbackStrategy` 明确要求 snapshot / stable rollback，且不是 `without_snapshot`
  - `rollback_point.status=created`
  - `snapshotPath` 位于本地快照根目录内，`projectPath` 位于代码输出根目录内
- 恢复前会把当前失败现场备份为 `failed_generation_<taskId>_<time>`，并沿用源码目录忽略规则过滤 `.git`、`node_modules`、`dist`、`target` 等目录
- `AppServiceImpl` 已在生成失败、后台构建失败、空项目失败、自动修复最终失败等分支先尝试回滚，再发送最终 `generation_error`
- `generation_error` payload 现在会在存在 `rollback_restore` artifact 时一并携带恢复结果；同时发送 `stage=rollback` 的 `agent_event`
- `GenerationOrchestrationMetricsCollector` 新增 `generation_orchestration_rollback_restore_total`，按 `status/reason` 记录真实回滚尝试结果
- 新增 `GenerationRollbackRestoreServiceTest` 覆盖 opt-in 恢复、手动策略跳过、回滚点未创建跳过，以及失败现场备份保留
- 最新验证结果：
  - `$env:JAVA_HOME='D:\java\jdk21'; $env:Path='D:\java\jdk21\bin;' + $env:Path; .\mvnw.cmd "-Dtest=GenerationRollbackRestoreServiceTest,GenerationDiffSummaryServiceTest,GenerationRollbackPointServiceTest,AgentGenerationOrchestratorTest,BuildFixAgentNodeTest,ReviewAgentNodeTest,ChangePlanTest" "-Dmaven.resources.skip=true" "-DskipTests=false" test`
  - `Tests run: 18`, `Failures: 0`, `Errors: 0`

### 3.16 Patch-first 实际落盘结果闭环

- 仍不引入外部 Git 服务或 Go workspace kernel；本阶段基于本地 `diff_summary` 对齐 `change_plan`，先完成 Java 单体内的可观测闭环
- 新增 `PatchResult` 标准 artifact，输出 `status/appId/taskId/plannedAddFiles/plannedModifyFiles/plannedDeleteFiles/actualAddedFiles/actualModifiedFiles/actualDeletedFiles/unplannedFiles/missingPlannedFiles/matchedFileCount/reason`
- 新增 `GenerationPatchResultService`，在生成成功并产出 `diff_summary` 后，对比计划文件范围和真实落盘 diff：
  - `applied`：实际新增 / 修改 / 删除文件与 `change_plan` 对齐
  - `drifted`：存在计划外变更或计划内文件未落盘
  - `skipped`：缺少 `change_plan`、缺少有效 `diff_summary`，或当前是新建项目全量生成
- `AppServiceImpl` 已在成功链路发送 `stage=patch` 的 `agent_event`，并把 `patch_result` 写回本次 `GenerationPreparation.artifacts()`
- `generation_error` payload 会在已有 `patch_result` 时一并携带，方便前端或后续回滚逻辑判断失败前实际落盘是否偏离计划
- `GenerationOrchestrationMetricsCollector` 新增 `generation_orchestration_patch_result_total`，按 `status/reason` 记录 patch-first 实际落盘结果
- 新增 `GenerationPatchResultServiceTest` 覆盖对齐、计划外变更、计划内未落盘和 diff 跳过场景
- 最新验证结果：
  - `$env:JAVA_HOME='D:\java\jdk21'; $env:Path='D:\java\jdk21\bin;' + $env:Path; .\mvnw.cmd "-Dtest=GenerationPatchResultServiceTest,GenerationDiffSummaryServiceTest,GenerationRollbackRestoreServiceTest,GenerationRollbackPointServiceTest,AgentGenerationOrchestratorTest,BuildFixAgentNodeTest,ReviewAgentNodeTest,ChangePlanTest" "-Dmaven.resources.skip=true" "-DskipTests=false" test`
  - `Tests run: 22`, `Failures: 0`, `Errors: 0`

### 3.17 独立补丁执行器 MVP

- 新增 `PatchApplyResult` 标准 artifact，输出 `schemaVersion/provider/status/appId/taskId/projectPath/plannedOperationCount/appliedOperationCount/rejectedOperationCount/appliedFiles/rejectedOperations/reason`
- 新增 `PatchOperation` 结构化补丁操作，覆盖 `add / modify / replace / delete`
- 新增 `GenerationPatchApplyService`，在落盘前统一校验：
  - 路径必须位于项目根目录内
  - 操作必须落在 `change_plan` 声明范围内
  - 变更计划缺失、空操作、越界路径、计划外操作都会直接拒绝，不产生半写入
- `FileWriteTool`、`FileModifyTool`、`FileDeleteTool` 已收口到同一补丁执行路径，避免各工具各自维护写文件逻辑
- 新增 `GenerationPatchApplyServiceTest`，覆盖成功落盘、计划外拒绝、非法路径拒绝、空计划跳过和 artifact 输入场景
- 最新验证结果：
  - `$env:JAVA_HOME='D:\java\jdk21'; $env:Path='D:\java\jdk21\bin;' + $env:Path; .\mvnw.cmd "-Dtest=GenerationPatchApplyServiceTest,GenerationPatchResultServiceTest,GenerationDiffSummaryServiceTest,GenerationRollbackRestoreServiceTest,GenerationRollbackPointServiceTest,ChangePlanTest" "-Dmaven.resources.skip=true" "-DskipTests=false" test`
  - `Tests run: 18`, `Failures: 0`, `Errors: 0`

## 4. 当前未实现部分

### 4.1 Recipe 库

- 登录 / 注册 / 权限 recipe
- CRUD / 列表 / 搜索分页 recipe
- Dashboard / 图表 recipe
- 表单 / 设置页 recipe
- 数据库服务接入 recipe

### 4.2 标准化 ChangePlan

- 新增文件
- 修改文件
- 删除文件
- 影响模块
- 验证等级
- 回滚策略

当前已落地标准 `change_plan` 契约、payload 恢复、路径归一化、Review 门禁校验、生成前本地 `rollback_point` artifact、生成后本地 `diff_summary` artifact、失败后的 opt-in 本地快照恢复，以及基于真实落盘 diff 的 `patch_result` 结果闭环。已补上独立补丁执行器 MVP，并把基础文件工具收口到统一落盘路径；后续仍可继续扩展更细粒度的内容级 patch 和批量事务能力。

### 4.3 语义索引 MVP

- 文件索引持久化
- 符号级索引
- 以索引代替当前文件扫描
- 结合 `ripgrep + FTS5 + tree-sitter` 的语义检索

当前仍是轻量扫描 + 关键词匹配阶段。

### 4.4 Git / Snapshot 一等公民方案

- 自动 commit
- 标准回滚点
- 变更前后 diff 可追踪
- 失败自动回滚到稳定版本
- 结果直接输出 commit id / rollback point

当前已将本地快照提升为生成主流程里的标准 `rollback_point` artifact，并在服务层 `generation_error` 事件中透出回滚点元数据；生成成功后会基于本地快照输出标准 `diff_summary` artifact，生成失败后也会在策略 opt-in 时恢复到本地快照。尚未接入自动 commit；短期内不要求外部 Git 服务，后续如需 commit id 再优先抽象本地 Git CLI 适配层。

### 4.5 指标闭环

当前已落地编排层基础指标：上下文字符数、精选文件数、索引文件数、DAG 节点耗时、编排总耗时、质量门禁结果、patch-first 次数、BuildFix 启用次数、回滚策略计划次数、失败后本地快照恢复结果次数、patch-first 实际落盘结果次数。

仍未闭环的部分：

- 单次生成 token 与编排任务关联
- 真实改修成功率
- 自动修复次数
- 用户平均等待时间
- Grafana 面板和告警规则按新指标更新

### 4.6 Go Workspace Kernel

- Go 独立 workspace 服务
- SQLite 元数据管理
- FTS5 检索
- tree-sitter 符号索引
- 补丁执行器
- 统一进程管理

目前系统仍以 Java 单体控制逻辑为主；不过文件级补丁执行器已经先在 Java 内收口，后续若拆 Go kernel，可以直接承接这层契约。

## 5. 建议的后续工作顺序

建议按“先固化当前收益，再扩展知识和执行面”的顺序推进：

1. 补齐核心回归测试，优先覆盖轻 / 重链路、Vue 构建分层、回滚兜底
2. 建立指标埋点，先把上下文、耗时、成功率、回滚率量化出来（编排层基础指标已完成，成功率 / 真实回滚率待接执行闭环）
3. 定义并落地 `ChangePlan`
4. 把快照 / 回滚接入生成主流程的失败兜底
5. 做第一批高频 recipe
6. 做 Java 内语义索引 MVP
7. 最后再拆 Go workspace kernel

## 6. 当前状态总结

- 本轮已落地的核心收益是：prompt 优化入口、改修分流、上下文减重、patch-first 元数据、分层构建验证
- 这些优化已经把系统从“整仓重写 + 全量 build”推向“意图驱动 + 最小 patch + 分层校验 + 标准化变更计划”
- 路由判定、heavy path 和 BuildFix 门禁现在也已经单点化，减少了 Planner 与编排器之间的语义漂移
- 编排层指标已经接入 Micrometer，后续可以直接基于 Prometheus / Grafana 观察上下文规模、节点耗时、门禁结果和回滚计划分布
- 后续最值钱的工作仍然是 `测试 + 指标 + Recipe + 回滚联动 + 独立补丁执行器`

注：优化要求就是保证代码健壮性，可读性、可扩展性、遵行设计模式思维、开闭原则，每次完成工作后，都要更新该文件内任务状态和文件内容。已完成的工作要标注已完成
