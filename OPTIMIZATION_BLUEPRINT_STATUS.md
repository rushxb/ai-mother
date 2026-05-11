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

### 3.18 指标闭环第二阶段

- `MonitorContext` 已新增 `taskId`，生成主链路和后台构建链路都会把编排任务 ID 透传到 AI 模型监听器
- `AiModelMetricsCollector` 的 `ai_model_requests_total`、`ai_model_errors_total`、`ai_model_tokens_total`、`ai_model_response_duration_seconds` 已新增 `task_id` 标签，支持单次生成 token 与任务快照关联
- `GenerationOrchestrationMetricsCollector` 新增：
  - `generation_orchestration_user_wait_duration_seconds`：按 `orchestration_mode / target_type / status` 记录用户等待时间
  - `generation_orchestration_auto_repair_total`：按 `orchestration_mode / stage / status` 记录生成阶段和构建阶段自动修复次数
  - `generation_orchestration_patch_apply_total`：按 `provider / status / reason` 记录补丁执行器真实落盘结果
- `AppServiceImpl` 会在生成成功、失败、取消和后台构建结束时记录等待时间，并避免停止与后台线程同时收尾造成重复计数
- 自动修复链路已区分 `generation` 与 `build` 阶段，记录 `started / success / failed`，便于评估自动修复真实收益
- 补丁执行器已在 `GenerationPatchApplyService` 内统一记录 applied / rejected / skipped，为真实改修成功率提供更前置的执行层口径
- `grafana/ai_model_grafana_config.json` 已新增“生成编排闭环”面板：平均用户等待时间、真实改修对齐率、自动修复趋势、补丁执行结果分布、任务级 Token 消耗 Top10
- 新增 `alert_rules.yml` 并在 `prometheus.yml` 中启用，覆盖用户等待时间过高、真实改修对齐率偏低、补丁拒绝次数激增三类告警
- 新增 `GenerationOrchestrationMetricsCollectorTest`，并扩展 `GenerationPatchApplyServiceTest` 验证补丁执行指标
- 最新验证结果：
  - `$env:JAVA_HOME='D:\java\jdk21'; $env:Path='D:\java\jdk21\bin;' + $env:Path; .\mvnw.cmd "-Dtest=GenerationOrchestrationMetricsCollectorTest,GenerationPatchApplyServiceTest,GenerationPatchResultServiceTest,GenerationDiffSummaryServiceTest,GenerationRollbackRestoreServiceTest,GenerationRollbackPointServiceTest,ChangePlanTest" "-Dmaven.resources.skip=true" "-DskipTests=false" test`
  - `Tests run: 19`, `Failures: 0`, `Errors: 0`

### 3.19 Recipe 库 MVP

- 新增 `GenerationRecipe` 与 `GenerationRecipeLibrary`，内置覆盖登录 / 注册 / 权限、CRUD / 列表 / 搜索分页、Dashboard / 图表、表单 / 设置页、Database 服务接入五类高频 recipe
- `GenerationAgentSupport` 已支持 recipe 匹配、recipe 模块补全和上下文文件 hint 注入；数据库、CRUD、搜索分页等需求也会被识别为复杂生成场景
- `PlannerAgentNode` 会在 `requirements` artifact 中输出 `recipeIds` 与结构化 `recipes`
- `ContextAgentNode` 会结合精简项目上下文再次匹配 recipe，并在 `context_summary` 中透传
- `CodeAgentNode` 会把匹配 recipe 压缩进最终 `generation_spec.enhancedPrompt`，让模型按稳定实现步骤、验证提示和 Database 边界生成
- 当前 recipe 仍为 Java 内置 MVP，后续可替换为配置化 recipe、语义检索 recipe 或 workspace kernel 侧 recipe registry
- 最新验证结果：
  - `$env:JAVA_HOME='D:\java\jdk21'; $env:Path='D:\java\jdk21\bin;' + $env:Path; .\mvnw.cmd "-Dtest=GenerationRecipeLibraryTest,GenerationAgentSupportTest,CodeAgentNodeTest,AgentGenerationOrchestratorTest,GenerationRoutingSupportTest" "-Dmaven.resources.skip=true" "-DskipTests=false" test`
  - `Tests run: 14`, `Failures: 0`, `Errors: 0`

### 3.20 Java 内语义索引 MVP

- 新增 `WorkspaceSemanticIndexService`、`WorkspaceSemanticIndex`、`WorkspaceSemanticIndexEntry`、`WorkspaceSemanticSearchHit`，在项目工作区内落地轻量持久化语义索引
- 索引文件会写入项目内 `.ai-code-index/semantic-index.json`，并基于工作区签名自动复用 / 刷新，避免上下文精选和项目搜索反复整树扫描
- 当前索引覆盖 `src / public / backend` 及关键配置文件，记录相对路径、文件名、扩展名、截断内容摘要和可搜索 terms，形成 Java 单体内的第一阶段“文件级索引持久化”
- `GenerationAgentSupport` 现已复用该索引做 `indexedFileCount` 统计、候选文件召回和目录 hint 扩展，上下文减重链路不再维护独立文件遍历实现
- `ProjectSearchTool` 已切换为基于语义索引的搜索结果输出，返回命中类型和分数，为后续接更细粒度符号索引或 FTS 检索保留统一接口
- `ProjectWorkspaceSupport` 已把 `.ai-code-index` 纳入忽略目录，避免索引元数据反向污染快照、diff、回滚和补丁链路
- 当前阶段已完成“文件索引持久化 + 以索引替代当前文件扫描”的 MVP；符号级索引与 `ripgrep + FTS5 + tree-sitter` 深化检索仍可作为下一阶段扩展
- 新增 `WorkspaceSemanticIndexServiceTest`，并复用 `GenerationAgentSupportTest` 验证上下文选择仍保持意图命中和预算约束
- 最新验证结果：
  - `$env:JAVA_HOME='D:\java\jdk21'; $env:Path='D:\java\jdk21\bin;' + $env:Path; .\mvnw.cmd "-Dtest=WorkspaceSemanticIndexServiceTest,GenerationAgentSupportTest,GenerationRecipeLibraryTest" "-Dmaven.resources.skip=true" "-DskipTests=false" test`
  - `Tests run: 9`, `Failures: 0`, `Errors: 0`

### 3.21 Java 内语义索引完整收口

- 已将索引 schema 升级到 `v2`，索引条目新增 `symbols`，可从 Vue / JS / TS / Java / Go 等源码中轻量提取函数、类、接口、类型、常量、组件名等符号
- `WorkspaceSemanticSearchHit` 已补齐 `recallSource`、`matchedTerms`、`matchedSymbols`，搜索命中现在能区分 path / file_name / content / symbol / selected_file，并返回可解释的召回依据
- `WorkspaceSemanticIndexService` 新增 `countIndexedSymbols` 与 `describeFiles`，支持上下文链路统计符号规模，并为通过意图 hint 选中的文件补齐索引元数据
- 索引构建增加同工作区重建锁，避免 Planner / Context 并行首次召回时重复构建和并发写入；索引目录不可写时会降级为内存索引，不影响当前召回链路
- `GenerationAgentSupport` 已把索引命中摘要写入项目上下文文本和 `ProjectContextPackage.indexHits`，并透传 `indexedSymbolCount`
- `PlannerAgentNode` 已在 `requirements` artifact 中输出 `contextRecallSource`、`contextRecallQuery`、`indexHits`；`ContextAgentNode` 已在 `context_summary` artifact 中输出 `indexedSymbolCount` 与 `indexHits`
- `ProjectSearchTool` 的结果已展示召回来源、匹配词和匹配符号，工具搜索和编排上下文共用同一套索引解释模型
- `GenerationOrchestrationMetricsCollector` 与 `AgentGenerationOrchestrator` 已新增单次 `indexed_symbols` 和 `index_hits` 指标，语义索引命中量进入编排可观测闭环
- 本需求点已完成 Java 单体内的“持久化文件索引 + 轻量符号索引 + 索引驱动召回 + artifact 透传 + 指标闭环 + 工具搜索解释性输出”，不再遗留独立的索引后续项
- 最新验证结果：
  - `$env:JAVA_HOME='D:\java\jdk21'; $env:Path='D:\java\jdk21\bin;' + $env:Path; .\mvnw.cmd "-Dtest=WorkspaceSemanticIndexServiceTest,GenerationAgentSupportTest,GenerationOrchestrationMetricsCollectorTest,AgentGenerationOrchestratorTest" "-Dmaven.resources.skip=true" "-DskipTests=false" test`
  - `Tests run: 12`, `Failures: 0`, `Errors: 0`

### 3.22 本地 Git 自动提交闭环

- 新增 `GenerationCommitResult` 标准 artifact，输出 `status/appId/taskId/projectPath/commitId/shortCommitId/branch/committedFileCount/committedFiles/reason`
- 新增 `GenerationCommitService`，在生成成功后基于 `diff_summary` 里的真实变更文件做最小范围 `git add` / `git commit`，不会把仓库内其他无关脏改动一起提交
- 提交范围限定为当前生成工作区目录下的真实新增 / 修改 / 删除文件；缺少 diff、没有真实变更、工作区不在 Git 仓库内时会明确 `skipped`
- `AppServiceImpl` 已在成功链路发送 `stage=commit` 的 `agent_event`，并把 `generation_commit` 写回本次 `GenerationPreparation.artifacts()`
- `generation_error` payload 现在会在存在 `generation_commit` 时一并携带 commit 元数据，便于前端或后续恢复链路追踪“最近一次已落库提交”
- `GenerationOrchestrationMetricsCollector` 新增 `generation_orchestration_commit_total`，按 `provider/status/reason` 记录本地 Git 提交结果
- 新增 `GenerationCommitServiceTest`，并扩展 `GenerationOrchestrationMetricsCollectorTest` 覆盖提交成功与指标落库
- 最新验证结果：
  - `$env:JAVA_HOME='D:\java\jdk21'; $env:Path='D:\java\jdk21\bin;' + $env:Path; .\mvnw.cmd "-Dtest=GenerationCommitServiceTest,GenerationOrchestrationMetricsCollectorTest,GenerationRollbackRestoreServiceTest,GenerationRollbackPointServiceTest,GenerationPatchResultServiceTest" "-Dmaven.resources.skip=true" "-DskipTests=false" test`
  - `Tests run: 12`, `Failures: 0`, `Errors: 0`

### 3.23 AppServiceImpl 事件与收尾回归测试已完成

- 新增 `AppServiceImplRegressionTest`，聚焦 `AppServiceImpl` 内部真正负责收口的私有方法回归，不改动现有生产链路
- 已覆盖 `buildGenerationErrorData(...)` 对 `rollback_point / diff_summary / patch_result / generation_commit / rollback_restore` 的透传，避免后续改动把失败事件 payload 悄悄裁掉
- 已覆盖 `buildDiffSummaryEventData(...)`、`buildPatchResultEventData(...)`、`buildCommitResultEventData(...)`、`buildRollbackRestoreEventData(...)`，锁定 `agent/stage/status/summary/taskId/artifact` 结构
- 已覆盖 `completeGenerationSession(...)` 的幂等收尾行为，确认用户等待时间指标不会在重复收尾或取消分支里重复记数
- 这一步把蓝图里“`AppServiceImpl` 成功 / 失败 / 取消 / 后台构建事件 payload 需要补测试”的最核心收口点先补齐，减少后续继续演进 artifact 时的回归风险
- 最新验证结果：
  - `$env:JAVA_HOME='D:\java\jdk21'; $env:Path='D:\java\jdk21\bin;' + $env:Path; .\mvnw.cmd "-Dtest=AppServiceImplRegressionTest,AgentGenerationOrchestratorTest,GenerationDiffSummaryServiceTest,GenerationPatchResultServiceTest,GenerationRollbackRestoreServiceTest,GenerationRollbackPointServiceTest,GenerationCommitServiceTest,ChangePlanTest,ReviewAgentNodeTest,BuildFixAgentNodeTest" "-Dmaven.resources.skip=true" "-DskipTests=false" test`
  - `Tests run: 27`, `Failures: 0`, `Errors: 0`

### 3.24 提示词体系重构

- 已重构 `codegen-vue-project-system-prompt.txt`、`codegen-html-system-prompt.txt`、`codegen-multi-file-system-prompt.txt`、`codegen-routing-system-prompt.txt`，统一为“现有项目优先、最小改动、工具驱动、patch-first 对齐”的生成口径
- 已重构 `code-quality-check-system-prompt.txt`，补齐阻断项、质量项和 patch-first 一致性校验，避免只做表面语法检查
- 已重构 `image-collection-plan-system-prompt.txt` 与 `image-collection-system-prompt.txt`，让图片规划与收集输出严格贴合现有 Java 模型和工具边界
- 已收紧各 prompt 的输出约束，减少“完整重写式”生成倾向，统一到可解析、可执行、可继续修改的工作流
- 当前已完成提示词体系的第一轮收口，后续如需扩展，只围绕 schema 或工具边界做小幅增量即可

## 4. 当前未实现部分

### 4.1 Recipe 库

- 已完成内置 MVP：登录 / 注册 / 权限、CRUD / 列表 / 搜索分页、Dashboard / 图表、表单 / 设置页、数据库服务接入
- 后续仍可扩展为可配置 recipe registry、按项目技术栈筛选 recipe、以及结合语义索引的动态 recipe 检索

### 4.2 标准化 ChangePlan

- 新增文件
- 修改文件
- 删除文件
- 影响模块
- 验证等级
- 回滚策略

当前已落地标准 `change_plan` 契约、payload 恢复、路径归一化、Review 门禁校验、生成前本地 `rollback_point` artifact、生成后本地 `diff_summary` artifact、失败后的 opt-in 本地快照恢复，以及基于真实落盘 diff 的 `patch_result` 结果闭环。已补上独立补丁执行器 MVP，并把基础文件工具收口到统一落盘路径；后续仍可继续扩展更细粒度的内容级 patch 和批量事务能力。

### 4.3 语义索引 MVP

- 已完成：
  - 文件索引持久化
  - 以索引代替当前上下文精选和项目搜索的整树扫描
  - 符号级索引
  - 索引命中元数据透传到 Planner / Context artifact
  - 索引符号数与命中数进入 Micrometer 指标
  - 项目搜索工具输出召回来源、匹配词和匹配符号

当前已从“纯轻量扫描 + 关键词匹配”升级为“轻量持久化索引 + 轻量符号索引 + 索引驱动召回 + 可解释命中元数据 + 指标闭环”阶段。Java 单体内索引需求点已收口完成；`ripgrep / FTS5 / tree-sitter` 可归入未来 Go Workspace Kernel 的实现选型，不作为本轮语义索引的未完成项。

### 4.4 Git / Snapshot 一等公民方案（本地闭环已完成）

- 自动 commit
- 标准回滚点
- 变更前后 diff 可追踪
- 失败自动回滚到稳定版本
- 结果直接输出 commit id / rollback point

当前已将本地快照提升为生成主流程里的标准 `rollback_point` artifact，并在服务层 `generation_error` 事件中透出回滚点元数据；生成成功后会基于本地快照输出标准 `diff_summary` artifact，生成失败后也会在策略 opt-in 时恢复到本地快照。现在已补齐本地 Git 自动 commit 闭环，生成成功后会按 `diff_summary` 里的真实变更范围提交并输出 `generation_commit` artifact，成功和失败事件都会携带 commit 元数据。

当前这一项在 Java 单体内的本地闭环已完成。

### 4.5 指标闭环

当前已落地编排层基础指标：上下文字符数、精选文件数、索引文件数、索引符号数、索引命中数、DAG 节点耗时、编排总耗时、质量门禁结果、patch-first 次数、BuildFix 启用次数、回滚策略计划次数、失败后本地快照恢复结果次数、patch-first 实际落盘结果次数、本地 Git 提交结果次数。

本阶段已继续补齐：

- 单次生成 token 与编排任务关联：AI 模型指标新增 `task_id` 标签
- 真实改修成功率：已有 `patch_result` 对齐率，新增 `patch_apply` 执行层结果
- 自动修复次数：新增生成阶段 / 构建阶段自动修复 started / success / failed 指标
- 用户平均等待时间：新增用户等待耗时 timer
- Grafana 面板和告警规则：已新增编排闭环面板和 Prometheus 告警规则

后续仍可继续做更细的看板治理，例如按租户 / 模型 / 代码类型拆分 SLO，以及把告警接入真实 Alertmanager 通知渠道。

### 4.6 Go Workspace Kernel

- Go 独立 workspace 服务
- SQLite 元数据管理
- FTS5 检索
- tree-sitter 符号索引
- 补丁执行器
- 统一进程管理

目前系统仍以 Java 单体控制逻辑为主；不过文件级补丁执行器已经先在 Java 内收口，后续若拆 Go kernel，可以直接承接这层契约。

## 5. 待增强项

1. 更细粒度补丁执行
   现在已有 GenerationPatchApplyService MVP，但还是文件级 add / modify / replace / delete。后续若要继续提升改修精度，可再做内容级 patch、冲突报告和批量事务能力。
2. Recipe 扩展
   目前是 Java 内置 MVP。后续若需要更强领域化能力，可继续扩展为可配置 recipe registry、按项目技术栈筛选 recipe、结合语义索引动态检索 recipe。
3. 指标看板治理
   基础指标已经足够覆盖主链路，后续重点应转向按租户 / 模型 / 代码类型拆分 SLO，并把告警真正接入通知渠道。
4. Go Workspace Kernel
   这是长期架构方向，适合作为后续独立迁移，不建议混入当前小步优化。


## 6. 当前状态总结

- 本轮已落地的核心收益是：prompt 优化入口、改修分流、上下文减重、patch-first 元数据、分层构建验证
- 这些优化已经把系统从“整仓重写 + 全量 build”推向“意图驱动 + 最小 patch + 分层校验 + 标准化变更计划”
- 路由判定、heavy path 和 BuildFix 门禁现在也已经单点化，减少了 Planner 与编排器之间的语义漂移
- 编排层指标已经接入 Micrometer，Prometheus / Grafana 现在可观察上下文规模、节点耗时、门禁结果、回滚计划、补丁执行、真实改修对齐率、自动修复、用户等待时间以及本地 Git 提交结果
- 后续最值钱的工作仍然是 `更细粒度补丁执行 + Recipe 扩展 + 指标治理 + Go workspace kernel`

注：优化要求就是保证代码健壮性，可读性、可扩展性、遵行设计模式思维、开闭原则(可以接受现有代码重构，但需要保证不影响现有代码功能)，每次完成工作后，都要更新该文件内任务状态和文件内容。已完成的工作要标注已完成。
注：继续完成本优化项时，尽可能一次性把某个点都进行优化，不要落下“后续再做”的后续工作残留，不要留下拓展内容
