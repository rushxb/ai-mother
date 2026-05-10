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

当前已落地基础 `change_plan` 契约，但还没有把真实的文件 diff、补丁应用结果和回滚点联动成自动闭环。

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

仓库内已有手工快照 / diff 工具，但还没有进入生成主流程。

### 4.5 指标闭环

- 平均上下文大小
- 单次生成 token
- 计划阶段耗时
- 编码阶段耗时
- 校验阶段耗时
- 改修成功率
- 自动修复次数
- 回滚次数
- 用户平均等待时间

### 4.6 Go Workspace Kernel

- Go 独立 workspace 服务
- SQLite 元数据管理
- FTS5 检索
- tree-sitter 符号索引
- 补丁执行器
- 统一进程管理

目前系统仍以 Java 单体控制逻辑为主。

## 5. 建议的后续工作顺序

建议按“先固化当前收益，再扩展知识和执行面”的顺序推进：

1. 补齐核心回归测试，优先覆盖轻 / 重链路、Vue 构建分层、回滚兜底
2. 建立指标埋点，先把上下文、耗时、成功率、回滚率量化出来
3. 定义并落地 `ChangePlan`
4. 把快照 / 回滚接入生成主流程的失败兜底
5. 做第一批高频 recipe
6. 做 Java 内语义索引 MVP
7. 最后再拆 Go workspace kernel

## 6. 当前状态总结

- 本轮已落地的核心收益是：prompt 优化入口、改修分流、上下文减重、patch-first 元数据、分层构建验证
- 这些优化已经把系统从“整仓重写 + 全量 build”推向“意图驱动 + 最小 patch + 分层校验 + 标准化变更计划”
- 后续最值钱的工作仍然是 `测试 + 指标 + Recipe + 回滚联动 + 真正的文件 diff/patch 执行`
