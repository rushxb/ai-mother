package com.rush.rushaicodemother.model.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.rush.rushaicodemother.orchestration.governance.TenantGenerationControlPlaneSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 面向租户管理员的版本化生成控制面响应。 */
public record TenantGenerationControlPlaneVO(
        Long tenantId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant observedAt,
        BudgetVO budget,
        QueueVO queue,
        List<ScenarioCostVO> scenarioCosts,
        List<AdmissionBlockerVO> activeRejectionReasons,
        int contractVersion
) {
    public static final int CURRENT_CONTRACT_VERSION = 1;

    /** 从领域快照创建稳定的公共响应。 */
    public static TenantGenerationControlPlaneVO from(TenantGenerationControlPlaneSnapshot snapshot) {
        return new TenantGenerationControlPlaneVO(
                snapshot.tenantId(),
                snapshot.observedAt(),
                BudgetVO.from(snapshot.budget()),
                QueueVO.from(snapshot.queue()),
                snapshot.scenarioCosts().stream().map(ScenarioCostVO::from).toList(),
                snapshot.activeRejectionReasons().stream().map(AdmissionBlockerVO::from).toList(),
                CURRENT_CONTRACT_VERSION
        );
    }

    public record BudgetVO(
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant periodStart,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant periodEnd,
            long monthlyCreditLimit,
            long consumedCredit,
            long remainingCredit
    ) {
        private static BudgetVO from(TenantGenerationControlPlaneSnapshot.BudgetSummary source) {
            return new BudgetVO(source.periodStart(), source.periodEnd(),
                    source.monthlyCreditLimit(), source.consumedCredit(), source.remainingCredit());
        }
    }

    public record QueueVO(
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
        private static QueueVO from(TenantGenerationControlPlaneSnapshot.QueueSummary source) {
            return new QueueVO(
                    source.queuedTasks(), source.runningTasks(), source.waitingApprovalTasks(),
                    source.totalNonTerminalTasks(), source.heavyNonTerminalTasks(),
                    source.maxNonTerminalTasks(), source.maxHeavyTasks(),
                    source.remainingNonTerminalSlots(), source.remainingHeavySlots());
        }
    }

    public record ScenarioCostVO(
            String route,
            String targetCodeGenType,
            long settledTasks,
            long successfulDeliveries,
            long totalCreditCost,
            BigDecimal unitSuccessfulCreditCost
    ) {
        private static ScenarioCostVO from(
                TenantGenerationControlPlaneSnapshot.ScenarioCostSummary source) {
            return new ScenarioCostVO(
                    source.route(), source.targetCodeGenType(), source.settledTasks(),
                    source.successfulDeliveries(), source.totalCreditCost(),
                    source.unitSuccessfulCreditCost());
        }
    }

    public record AdmissionBlockerVO(String code, String message) {
        private static AdmissionBlockerVO from(
                TenantGenerationControlPlaneSnapshot.AdmissionBlocker source) {
            return new AdmissionBlockerVO(source.code(), source.message());
        }
    }
}
