# ADR：场景决策事实所有权

## 状态

已接受。

## 决策

`GenerationScenarioDecisionKernel` 是用户请求场景事实的唯一生成模块。`GenerationScenarioPreflight` 在工作区事实已解析、执行计划与最终准入尚未冻结时调用该内核，一次性产生不可变 `GenerationScenarioDecision`。

决策同时拥有：

- 结构化意图与目标工程类型；
- `READ_ONLY / WRITE` 可变性；
- 数据库等执行资源需求；
- 路由、fallback 和验证下限；
- 工具写入、fence 与破坏性审批权限；
- 词法规则版本与由实际运行策略生成的发布指纹。

`GenerationPipelineRequest` 只保存这一份决策；画像与路由访问器只是兼容投影。`GenerationExecutionPlanner`、资源预配、任务命令恢复与场景归因只消费该决策。

`GenerationRoutingSignal` 不暴露原始请求或 Prompt，只包含工程类型、工作区是否存在、运行遥测与同一份结构化画像。遥测策略只能组合这些事实，不能根据关键词或文本长度形成第二套场景语义。

`GenerationTaskCommand` schema 10 持久化决策本体与 `GenerationPreflightUsage`。旧的路由、画像与资源字段暂时保留为历史 JSON 兼容面，构造时必须与决策一致；历史 schema 缺失决策或预检用量时只允许确定性保守恢复。

## 有界 preflight

本地规则每个请求只解析一次 Prompt。只有低置信度画像会在路由前进入一次模型澄清，澄清可同时改变操作类型、路由、资源、工具权限、验证下限和模型档位。它必须满足：

- 先创建 task identity，再调用 provider；
- 已存在的幂等任务在分配新 taskId 和调用 provider 前直接返回；
- 先通过用户积分、用户/租户并发、Heavy 容量和最坏路由月度成本门禁；
- 最多 1 次根模型尝试、1 个模型回合和 2 次 provider 尝试，禁止工具、构建和修复；
- 物理调用绑定可计费 MonitorContext 和真实 taskId；
- 用量持久化到任务命令，worker 恢复时作为已消费预算；
- worker 只消费已冻结决策，不再二次调用意图澄清模型。

## 只读不变量

`EXPLAIN / AUDIT / PLAN` 必须同时满足：

- 路由为 `READ_ONLY`；
- 不预配数据库资源；
- 工具权限为 `READ_ONLY`；
- 验证下限来自同一路由决策。

这些约束由 `GenerationScenarioDecision` 构造器 fail-closed，不依赖 Prompt 约定。

## 已知边界

preflight 门禁是无持久副作用的保守预判，最终任务准入会在事务内重新检查并冻结积分。两者之间仍有短暂竞态窗口：窗口内容量变化可能导致已支付一次有界澄清成本后最终准入被拒绝。如要消除该窗口，需要引入可超时回收的两阶段 preflight reservation，不应使用长事务包裹 provider 调用。
