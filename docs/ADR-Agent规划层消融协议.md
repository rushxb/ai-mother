# ADR：Agent 规划层消融协议

## 状态

实验基础设施已接受，生产结构决策未决。当前没有真实模型三组对照数据，不删除现有节点，也不切换生产默认方案。

## 方案

| Variant | 实际执行语义 | 检查点语义 |
| --- | --- | --- |
| `NO_PLAN` | 保留模板、仓库上下文和运行必需的最小 `generation_spec/change_plan`，跳过架构规划与前置规则审查 | 单检查点 |
| `COMPACT_PLAN` | 在一个节点内顺序复用现有 Planner、Template、Context、Architect、Code、Review 和 RepairPolicy 逻辑 | 单检查点 |
| `CURRENT_DAG` | 保持当前逐节点规划 DAG，生产默认不变 | 每节点检查点 |

`GenerationPlanningVariant` 随 schema 8 的持久任务命令保存。恢复、重试和跨 worker 执行必须继续使用原 variant，禁止通过进程级开关或线程变量选择实验组。

## 证据口径

- 每个 variant 对同一 Benchmark catalog 完整执行，Prompt bundle 和模型指纹必须一致。
- 成功率、一次构建通过率、质量维度、总耗时、token 和积分成本使用完整 catalog；比较前必须核对任务 ID 集合、Prompt bundle 和模型指纹，不能只比较样本数量。
- 模型调用前准备耗时复用 `heavy_prepare` span，只统计实际进入规划层的 `HEAVY_EXPERT` 样本，观测率必须为 100%。
- 每条结果显式记录 variant；三组报告缺失、混组或身份不一致均拒绝比较。
- `NO_PLAN` 不改变工具安全策略。已有项目若无法召回目标文件，其空变更范围应被工具层拒绝；该失败是无规划方案缺失安全边界的有效实验结果，不能通过开放全仓写权限规避。

## 晋级门禁

更精简候选必须先通过现有绝对发布门禁，并同时满足：

1. 候选确实比基线更精简。
2. 成功率、一次构建通过率不下降。
3. 所有质量维度的评估率和通过率不下降。
4. 准备耗时完整观测。
5. 准备耗时 P90、总耗时 P90、token、积分成本均不得回退，且至少一项严格改善。

仅“更便宜”不能抵消质量下降。当前代码只证明协议可执行和门禁可判定，不证明 `NO_PLAN` 或 `COMPACT_PLAN` 胜出。

## 后续决策

真实三组报告通过门禁后再决定节点去留。若 `COMPACT_PLAN` 胜出，再把前置 `Review` 和 `BuildFix` 按真实职责收敛为 `PlanValidator` 与 `RepairPolicyCompiler`；若没有净收益，保留当前 DAG。真实代码 diff 审查和构建诊断修复仍属于后置 Agent 运行时，不由本 ADR 的前置规则节点替代。
