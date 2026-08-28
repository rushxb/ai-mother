package com.rush.rushaicodemother.orchestration.governance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 租户管理员可见的低敏、只读生成治理快照。 */
public record TenantGenerationControlPlaneSnapshot(
        Long tenantId,
        Instant observedAt,
        BudgetSummary budget,
        QueueSummary queue,
        List<ScenarioCostSummary> scenarioCosts,
        List<AdmissionBlocker> activeRejectionReasons
) {
    public TenantGenerationControlPlaneSnapshot {
        if (tenantId == null || tenantId <= 0 || observedAt == null
                || budget == null || queue == null
                || scenarioCosts == null || activeRejectionReasons == null) {
            throw new IllegalArgumentException("租户生成控制面快照不完整");
        }
        scenarioCosts = List.copyOf(scenarioCosts);
        activeRejectionReasons = List.copyOf(activeRejectionReasons);
    }

    /** 当前计费周期预算，消耗额允许高于当前配置上限以保留真实事实。 */
    public record BudgetSummary(
            Instant periodStart,
            Instant periodEnd,
            long monthlyCreditLimit,
            long consumedCredit,
            long remainingCredit
    ) {
        public BudgetSummary {
            if (periodStart == null || periodEnd == null || !periodStart.isBefore(periodEnd)
                    || monthlyCreditLimit <= 0 || consumedCredit < 0 || remainingCredit < 0
                    || remainingCredit != Math.max(0L, monthlyCreditLimit - consumedCredit)) {
                throw new IllegalArgumentException("租户生成预算摘要无效");
            }
        }
    }

    /** 当前排队和共享容量，不包含任务或用户身份。 */
    public record QueueSummary(
            int queuedTasks,
            int runningTasks,
            int waitingApprovalTasks,
            int totalNonTerminalTasks,
            int heavyNonTerminalTasks,
            int maxNonTerminalTasks,
            int maxHeavyTasks,
            int remainingNonTerminalSlots,
            int remainingHeavySlots
    ) {
        public QueueSummary {
            if (queuedTasks < 0 || runningTasks < 0 || waitingApprovalTasks < 0
                    || totalNonTerminalTasks < 0 || heavyNonTerminalTasks < 0
                    || maxNonTerminalTasks <= 0 || maxHeavyTasks <= 0
                    || remainingNonTerminalSlots < 0 || remainingHeavySlots < 0
                    || queuedTasks + runningTasks + waitingApprovalTasks != totalNonTerminalTasks
                    || heavyNonTerminalTasks > totalNonTerminalTasks
                    || remainingNonTerminalSlots
                    != Math.max(0, maxNonTerminalTasks - totalNonTerminalTasks)
                    || remainingHeavySlots != Math.max(0, maxHeavyTasks - heavyNonTerminalTasks)) {
                throw new IllegalArgumentException("租户生成排队摘要无效");
            }
        }
    }

    /** 全部已结算尝试成本除以成功交付数得到的场景单位成本。 */
    public record ScenarioCostSummary(
            String route,
            String targetCodeGenType,
            long settledTasks,
            long successfulDeliveries,
            long totalCreditCost,
            BigDecimal unitSuccessfulCreditCost
    ) {
        public ScenarioCostSummary {
            if (route == null || route.isBlank()
                    || targetCodeGenType == null || targetCodeGenType.isBlank()
                    || settledTasks <= 0 || successfulDeliveries <= 0
                    || successfulDeliveries > settledTasks || totalCreditCost < 0
                    || unitSuccessfulCreditCost == null
                    || unitSuccessfulCreditCost.signum() < 0) {
                throw new IllegalArgumentException("租户生成场景成本摘要无效");
            }
        }
    }

    /** 当前观察时点实际生效的准入阻断项，不代表历史拒绝次数。 */
    public record AdmissionBlocker(String code, String message) {
        public AdmissionBlocker {
            if (code == null || !code.matches("[a-z][a-z0-9_]{1,63}")
                    || message == null || message.isBlank()) {
                throw new IllegalArgumentException("租户生成准入阻断项无效");
            }
        }
    }
}
