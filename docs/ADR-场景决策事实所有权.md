# ADR：场景决策事实所有权

## 状态

已接受，分阶段迁移中。

## 决策

`GenerationScenarioDecisionKernel` 是用户请求场景事实的唯一生成模块。它在工作区事实已解析、执行计划与准入尚未冻结时，一次性产生不可变 `GenerationScenarioDecision`。

决策同时拥有：

- 结构化意图与目标工程类型；
- `READ_ONLY / WRITE` 可变性；
- 数据库等执行资源需求；
- 路由、fallback 和验证下限；
- 工具写入、fence 与破坏性审批权限；
- 词法规则版本与由实际运行策略生成的发布指纹。

`GenerationPipelineRequest` 只保存这一份决策；画像与路由访问器只是兼容投影。`GenerationExecutionPlanner`、资源预配、任务命令恢复与场景归因只消费该决策。

`GenerationTaskCommand` schema 9 持久化决策本体。旧的路由、画像与资源字段暂时保留为历史 JSON 兼容面，构造时必须与决策一致；历史 schema 缺失决策时只允许确定性保守恢复。

## 只读不变量

`EXPLAIN / AUDIT / PLAN` 必须同时满足：

- 路由为 `READ_ONLY`；
- 不预配数据库资源；
- 工具权限为 `READ_ONLY`；
- 验证下限来自同一路由决策。

这些约束由 `GenerationScenarioDecision` 构造器 fail-closed，不依赖 Prompt 约定。

## 未完成边界

低置信度模型澄清目前仍位于 worker 执行期，只能调整模型档位。下一阶段将其迁到准入前的有界 preflight seam，使澄清能在资源、路由、权限、SLA 和验证图冻结前修正完整决策。
