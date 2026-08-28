package com.rush.rushaicodemother.orchestration.governance;

import java.time.LocalDateTime;
import java.util.List;

/** 租户生成控制面所需的只读持久事实边界。 */
public interface TenantGenerationControlPlaneRepository {

    /**
     * 加载指定观察窗口内的租户生成事实。
     *
     * @param tenantId 租户编号
     * @param periodStart 当前计费周期开始时间（含）
     * @param observedBefore 本次观察截止时间（不含）
     * @return 预算、排队和已结算成功成本事实
     */
    ControlPlaneFacts load(Long tenantId,
                           LocalDateTime periodStart,
                           LocalDateTime observedBefore);

    /** 只包含持久层可证明的原始事实，不承载额度策略。 */
    record ControlPlaneFacts(
            long consumedCredit,
            QueueFacts queue,
            List<ScenarioCostFacts> scenarioCosts
    ) {
        public ControlPlaneFacts {
            if (consumedCredit < 0 || queue == null || scenarioCosts == null) {
                throw new IllegalArgumentException("租户生成控制面事实不完整");
            }
            scenarioCosts = List.copyOf(scenarioCosts);
        }
    }

    /** 当前非终态任务数，字段口径与准入策略保持一致。 */
    record QueueFacts(
            int queuedTasks,
            int runningTasks,
            int waitingApprovalTasks,
            int totalNonTerminalTasks,
            int heavyNonTerminalTasks
    ) {
        public QueueFacts {
            if (queuedTasks < 0 || runningTasks < 0 || waitingApprovalTasks < 0
                    || totalNonTerminalTasks < 0 || heavyNonTerminalTasks < 0) {
                throw new IllegalArgumentException("租户排队事实不能为负数");
            }
            if (queuedTasks + runningTasks + waitingApprovalTasks != totalNonTerminalTasks
                    || heavyNonTerminalTasks > totalNonTerminalTasks) {
                throw new IllegalArgumentException("租户排队事实不一致");
            }
        }
    }

    /** 按低基数实际路由和目标代码类型聚合的全部已结算尝试成本与成功数。 */
    record ScenarioCostFacts(
            String route,
            String targetCodeGenType,
            long settledTasks,
            long successfulDeliveries,
            long totalCreditCost
    ) {
        public ScenarioCostFacts {
            if (route == null || route.isBlank()
                    || targetCodeGenType == null || targetCodeGenType.isBlank()
                    || settledTasks <= 0 || successfulDeliveries <= 0
                    || successfulDeliveries > settledTasks || totalCreditCost < 0) {
                throw new IllegalArgumentException("租户场景成本事实无效");
            }
        }
    }
}
