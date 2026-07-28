package com.rush.rushaicodemother.orchestration.dag;

import java.util.Map;

/**
 * 判断持久化 DAG 检查点是否位于可自动恢复的确定性边界。
 */
public final class GenerationDagCheckpointRecoveryPolicy {

    private GenerationDagCheckpointRecoveryPolicy() {
    }

    /**
 * 返回{@code assess}。
 *
 * @param task 任务
 * @return 生成{@code Dag}检查点恢复策略
 */
    public static Assessment assess(GenerationOrchestrationTask task) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (task == null) {
            return rejected(Disposition.INVALID, "生成 DAG 检查点不存在");
        }
        if (!GenerationOrchestrationTask.supportsSchemaVersion(task.getSchemaVersion())) {
            return rejected(Disposition.INVALID, "生成 DAG 检查点版本不受支持");
        }
        AgentRuntimeState runtimeState = task.getRuntimeState();
        Map<String, String> nodeStatuses = task.getNodeStatuses();
        if (runtimeState == null || nodeStatuses == null) {
            return rejected(Disposition.INVALID, "生成 DAG 检查点状态不完整");
        }
        if (runtimeState == AgentRuntimeState.FAILED
                || "failed".equals(task.getStatus())
                || nodeStatuses.containsValue("failed")) {
            return rejected(Disposition.FAILED_REQUIRES_DECISION,
                    "失败的生成 DAG 节点需要显式恢复决策");
        }
        if (hasText(task.getCurrentNode()) || nodeStatuses.containsValue("running")) {
            return rejected(Disposition.AMBIGUOUS_NODE,
                    "执行中的生成 DAG 节点没有幂等契约，不能自动重派");
        }
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (Map.Entry<String, String> entry : nodeStatuses.entrySet()) {
            if (!hasText(entry.getKey()) || !"done".equals(entry.getValue())) {
                return rejected(Disposition.INVALID, "生成 DAG 检查点包含未知节点状态");
            }
        }
        if (runtimeState == AgentRuntimeState.COMPLETED) {
            return assessCompleted(task, nodeStatuses);
        }
        if (runtimeState.terminal() || !"running".equals(task.getStatus())) {
            return rejected(Disposition.INVALID, "生成 DAG 检查点生命周期不一致");
        }
        if (nodeStatuses.isEmpty()) {
            if (task.getCheckpointVersion() != 0L || hasText(task.getLastCompletedNode())) {
                return rejected(Disposition.INVALID, "生成 DAG 初始检查点包含不一致进度");
            }
            return accepted(Disposition.INITIAL_RESTART, "生成 DAG 尚未执行，可从初始边界恢复");
        }
        if (runtimeState == AgentRuntimeState.INITIALIZED
                || !hasText(task.getDagFingerprint())
                || !hasText(task.getLastCompletedNode())
                || !"done".equals(nodeStatuses.get(task.getLastCompletedNode()))
                || task.getCheckpointVersion() < nodeStatuses.size()) {
            return rejected(Disposition.INVALID, "生成 DAG 节点边界检查点不完整");
        }
        return accepted(Disposition.NODE_BOUNDARY_RESUME, "生成 DAG 可从已完成节点边界恢复");
    }

    /** 返回{@code assess}完成。 */
    private static Assessment assessCompleted(GenerationOrchestrationTask task,
                                              Map<String, String> nodeStatuses) {
        if (!"completed".equals(task.getStatus())
                || nodeStatuses.isEmpty()
                || !hasText(task.getDagFingerprint())
                || !hasText(task.getLastCompletedNode())
                || !"done".equals(nodeStatuses.get(task.getLastCompletedNode()))
                || task.getCheckpointVersion() <= nodeStatuses.size()
                || !"success".equals(task.getTerminationReason())) {
            return rejected(Disposition.INVALID, "生成 DAG 成功终态检查点不完整");
        }
        return accepted(Disposition.COMPLETED_REUSE, "生成 DAG 已完成，可复用准备产物继续模型阶段");
    }

    private static Assessment accepted(Disposition disposition, String reason) {
        return new Assessment(disposition, reason);
    }

    private static Assessment rejected(Disposition disposition, String reason) {
        return new Assessment(disposition, reason);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record Assessment(Disposition disposition, String reason) {

        public boolean automaticallyRecoverable() {
            return disposition.automaticallyRecoverable();
        }
    }

    public enum Disposition {
        INITIAL_RESTART(true),
        NODE_BOUNDARY_RESUME(true),
        COMPLETED_REUSE(true),
        AMBIGUOUS_NODE(false),
        FAILED_REQUIRES_DECISION(false),
        INVALID(false);

        private final boolean automaticallyRecoverable;

        Disposition(boolean automaticallyRecoverable) {
            this.automaticallyRecoverable = automaticallyRecoverable;
        }

        public boolean automaticallyRecoverable() {
            return automaticallyRecoverable;
        }
    }
}
