package com.rush.rushaicodemother.service.credit;

import com.rush.rushaicodemother.model.enums.UserCreditTransactionType;
import com.rush.rushaicodemother.orchestration.delivery.GenerationCostSummary;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.CreditTransaction;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.GenerationCreditTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 将预授权、Provider 成本事实与最终账本投影为用户可解释的成本合同。 */
@Service
@RequiredArgsConstructor
public class GenerationTaskCostProjectionService {

    private final UserCreditPersistenceService persistenceService;
    private final GenerationUserBillingPolicy billingPolicy;
    private final UserCreditCostCalculator costCalculator;

    /**
     * 查询任务成本快照。
     *
     * <p>金额只取结构化流水和已结算任务字段；流水备注不参与任何事实推导。</p>
     */
    @Transactional(readOnly = true)
    public GenerationCostSummary project(String taskId, boolean terminal) {
        GenerationCreditTask task = persistenceService.findGenerationTask(taskId);
        if (task == null) {
            return null;
        }
        CreditTransaction reservation = persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION, taskId);
        Long maximumReservedCredit = reservationAmount(task, reservation);
        ProviderCostObservation observation =
                persistenceService.loadTaskProviderCostObservation(taskId);
        UserBillingDecision completedDecision = billingPolicy.decide(completedObservation(observation));
        long provisionalCreditCost = costCalculator.calculate(
                completedDecision.chargeableTokens());
        String waiverReason = waiverReason(observation, completedDecision.waivedTokens());

        if (!task.settled()) {
            String settlementStatus = terminal ? "pending" : "reserved";
            String summary = terminal
                    ? "任务已结束，成本正在结算；当前暂估 " + provisionalCreditCost + " 积分"
                    : runningSummary(maximumReservedCredit, provisionalCreditCost);
            return new GenerationCostSummary(
                    settlementStatus, null, null, null,
                    maximumReservedCredit, completedDecision.providerObservedTokens(),
                    provisionalCreditCost, null, null,
                    completedDecision.waivedTokens(), waiverReason, summary);
        }

        if (observation.hasPendingAttempts()) {
            throw new IllegalStateException("已结算任务仍存在未完成的 Provider 调用");
        }
        long actualCreditCost = requireSettledValue(task.creditCost(), "实际扣费");
        long chargeableTokens = requireSettledValue(task.totalTokens(), "计费 Token");
        if (completedDecision.chargeableTokens() != chargeableTokens) {
            throw new IllegalStateException("Provider 计费 Token 与任务结算结果不一致");
        }
        Long refundedCredit = settledRefund(task, reservation, maximumReservedCredit,
                actualCreditCost, chargeableTokens);
        String refundReason = refundedCredit != null && refundedCredit > 0
                ? "actual_cost_below_reserved" : null;
        return new GenerationCostSummary(
                "settled", chargeableTokens, actualCreditCost, actualCreditCost > 0,
                maximumReservedCredit, completedDecision.providerObservedTokens(),
                null, refundedCredit, refundReason,
                completedDecision.waivedTokens(), waiverReason,
                settledSummary(actualCreditCost, refundedCredit, completedDecision.waivedTokens()));
    }

    private ProviderCostObservation completedObservation(ProviderCostObservation observation) {
        Objects.requireNonNull(observation, "Provider 成本事实不能为空");
        return new ProviderCostObservation(
                observation.successfulTokens(),
                observation.cancelledTokens(),
                observation.timedOutTokens(),
                observation.failedTokens(),
                0L);
    }

    private Long reservationAmount(GenerationCreditTask task, CreditTransaction reservation) {
        if (reservation == null) {
            return null;
        }
        assertOwnedTransaction(task, reservation);
        if (reservation.changeAmount() >= 0) {
            throw new IllegalStateException("生成任务预授权流水金额不合法");
        }
        return Math.negateExact(reservation.changeAmount());
    }

    private Long settledRefund(GenerationCreditTask task,
                               CreditTransaction reservation,
                               Long maximumReservedCredit,
                               long actualCreditCost,
                               long chargeableTokens) {
        if (reservation == null) {
            return 0L;
        }
        CreditTransaction settlement = persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_SETTLEMENT, task.taskId());
        if (settlement == null) {
            throw new IllegalStateException("已结算任务缺少预授权结算流水");
        }
        assertOwnedTransaction(task, settlement);
        if (settlement.tokenCount() == null || settlement.tokenCount() != chargeableTokens) {
            throw new IllegalStateException("生成任务结算流水 Token 与任务结算结果不一致");
        }
        long expectedDelta = Math.subtractExact(maximumReservedCredit, actualCreditCost);
        if (settlement.changeAmount() != expectedDelta) {
            throw new IllegalStateException("生成任务结算流水与实际扣费不一致");
        }
        return Math.max(0L, settlement.changeAmount());
    }

    private void assertOwnedTransaction(GenerationCreditTask task,
                                        CreditTransaction transaction) {
        if (task.userId() != transaction.userId()
                || !Objects.equals(task.tenantId(), transaction.tenantId())
                || !Objects.equals(task.taskId(), transaction.bizId())) {
            throw new IllegalStateException("生成任务成本流水身份不一致");
        }
    }

    private long requireSettledValue(Long value, String fieldName) {
        if (value == null || value < 0) {
            throw new IllegalStateException("已结算任务缺少" + fieldName);
        }
        return value;
    }

    private String waiverReason(ProviderCostObservation observation, long waivedTokens) {
        if (waivedTokens <= 0) {
            return null;
        }
        boolean timedOut = observation.timedOutTokens() > 0;
        boolean failed = observation.failedTokens() > 0;
        if (timedOut && failed) {
            return "provider_timeout_and_failure";
        }
        if (timedOut) {
            return "provider_timeout";
        }
        if (failed) {
            return "provider_failure";
        }
        return "billing_policy";
    }

    private String runningSummary(Long maximumReservedCredit, long provisionalCreditCost) {
        if (maximumReservedCredit == null) {
            return "当前暂估消耗 " + provisionalCreditCost + " 积分";
        }
        return "已冻结 " + maximumReservedCredit
                + " 积分，当前暂估消耗 " + provisionalCreditCost + " 积分";
    }

    private String settledSummary(long actualCreditCost,
                                  Long refundedCredit,
                                  long waivedTokens) {
        StringBuilder summary = new StringBuilder("实际扣费 ")
                .append(actualCreditCost).append(" 积分");
        if (refundedCredit != null && refundedCredit > 0) {
            summary.append("，已退还 ").append(refundedCredit).append(" 积分");
        }
        if (waivedTokens > 0) {
            summary.append("，已免除 ").append(waivedTokens).append(" Token 对应费用");
        }
        return summary.toString();
    }
}
