package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.UserCreditTransactionType;
import com.rush.rushaicodemother.monitor.GenerationCreditMetricsCollector;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.credit.AdminCreditAdjustmentCommand;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationCommand;
import com.rush.rushaicodemother.service.credit.GenerationUserBillingPolicy;
import com.rush.rushaicodemother.service.credit.ProviderCostObservation;
import com.rush.rushaicodemother.service.credit.UserBillingDecision;
import com.rush.rushaicodemother.service.credit.UserCreditCostCalculator;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.CreditAccount;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.CreditTransaction;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.GenerationCreditTask;
import com.rush.rushaicodemother.service.credit.UserCreditPersistenceService.NewCreditTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * 用户额度服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCreditServiceImpl implements UserCreditService {

    private static final int MAX_REMARK_LENGTH = 512;
    private static final String ACCOUNT_INITIALIZATION_REMARK = "管理员创建用户初始化积分";

    private final UserCreditPersistenceService persistenceService;
    private final UserCreditCostCalculator costCalculator;
    private final GenerationCreditMetricsCollector creditMetricsCollector;
    private final GenerationUserBillingPolicy billingPolicy;

    /**
 * 确保{@code Has}额度已达到可用状态。
 *
 * @param userId 用户编号
 */
    @Override
    public void ensureHasCredit(Long userId) {
        if (!hasPositiveId(userId)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        CreditAccount account = persistenceService.findActiveAccount(userId);
        if (account == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        if (account.balance() <= 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "积分不足，请联系管理员充值");
        }
    }

    /**
 * 初始化额度。
 *
 * @param userId 用户编号
 * @param initialCredit {@code initialCredit} 对应的调用参数
 * @param adminUserId 管理端用户编号
 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initializeCredit(Long userId, Long initialCredit, Long adminUserId) {
        if (!hasPositiveId(userId) || initialCredit == null || initialCredit <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "初始积分参数不合法");
        }
        requireAdmin(adminUserId);

        CreditAccount account = requireLockedAccount(userId);
        String bizId = String.valueOf(userId);
        CreditTransaction existing = persistenceService.findTransaction(
                UserCreditTransactionType.ACCOUNT_INITIALIZATION,
                bizId
        );
        if (existing != null) {
            validateExistingInitialization(existing, userId, initialCredit, adminUserId);
            return;
        }

        long balanceAfter = calculateBalanceAfter(account.balance(), initialCredit);
        persistenceService.updateBalance(userId, balanceAfter);
        persistenceService.appendTransaction(new NewCreditTransaction(
                userId,
                initialCredit,
                balanceAfter,
                UserCreditTransactionType.ACCOUNT_INITIALIZATION,
                bizId,
                ACCOUNT_INITIALIZATION_REMARK,
                adminUserId,
                null
        ));
    }

    /**
 * 返回{@code adjust}额度按管理端。
 *
 * @param command 命令
 * @return 计算或处理后的数值结果
 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public long adjustCreditByAdmin(AdminCreditAdjustmentCommand command) {
        ValidatedAdjustment adjustment = validateAdjustment(command);
        CreditAccount account = requireLockedAccount(adjustment.userId());
        CreditTransaction existing = persistenceService.findTransaction(
                UserCreditTransactionType.ADMIN_ADJUST,
                adjustment.requestId()
        );
        if (existing != null) {
            validateExistingAdjustment(existing, adjustment);
            return existing.balanceAfter();
        }

        long balanceAfter = calculateBalanceAfter(account.balance(), adjustment.changeAmount());
        persistenceService.updateBalance(adjustment.userId(), balanceAfter);
        persistenceService.appendTransaction(new NewCreditTransaction(
                adjustment.userId(),
                adjustment.changeAmount(),
                balanceAfter,
                UserCreditTransactionType.ADMIN_ADJUST,
                adjustment.requestId(),
                adjustment.remark(),
                adjustment.adminUserId(),
                null
        ));
        return balanceAfter;
    }

    /**
 * 处理{@code charge}生成任务。
 *
 * @param taskId 任务编号
 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void chargeGenerationTask(String taskId) {
        String normalizedTaskId = requireTaskId(taskId);
        GenerationCreditTask task = persistenceService.lockGenerationTask(normalizedTaskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "生成任务不存在，无法结算积分");
        }
        if (task.settled()) {
            return;
        }

        CreditTransaction settlement = persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_SETTLEMENT,
                normalizedTaskId
        );
        if (settlement != null) {
            recoverReservedGenerationSettlement(task, settlement);
            return;
        }

        CreditTransaction existing = persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_CHARGE,
                normalizedTaskId
        );
        if (existing != null) {
            recoverGenerationSettlement(task, existing);
            return;
        }

        CreditTransaction reservation = persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION,
                normalizedTaskId
        );
        if (reservation != null) {
            settleReservedGenerationTask(task, reservation);
            return;
        }

        settleLegacyGenerationTask(task);
    }

    /**
 * 处理{@code reserve}生成任务。
 *
 * @param command 命令
 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reserveGenerationTask(GenerationCreditReservationCommand command) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (command == null || !hasPositiveId(command.userId())
                || !hasPositiveId(command.tenantId()) || command.reservedCredit() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成任务积分预授权参数不合法");
        }
        String taskId = requireTaskId(command.taskId());
        String pricingReference = normalizePricingReference(command.pricingReference());
        CreditAccount account = requireLockedAccount(command.userId());
        CreditTransaction existing = persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION,
                taskId
        );
        if (existing != null) {
            validateExistingReservation(existing, command, pricingReference);
            return;
        }
        if (account.balance() < command.reservedCredit()) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "积分不足，当前任务至少需要预留 " + command.reservedCredit() + " 积分"
            );
        }

        long balanceAfter = account.balance() - command.reservedCredit();
        persistenceService.updateBalance(command.userId(), balanceAfter);
        persistenceService.appendTransaction(new NewCreditTransaction(
                command.userId(),
                command.tenantId(),
                -command.reservedCredit(),
                balanceAfter,
                UserCreditTransactionType.GENERATION_RESERVATION,
                taskId,
                reservationRemark(pricingReference),
                null,
                null
        ));
    }

    /** 处理{@code settle}{@code Reserved}生成任务。 */
    private void settleReservedGenerationTask(GenerationCreditTask task,
                                              CreditTransaction reservation) {
        long reservedCredit = validateReservation(task, reservation);
        UserBillingDecision billingDecision = requireBillingDecision(task.taskId());
        long totalTokens = billingDecision.chargeableTokens();
        long expectedCreditCost = costCalculator.calculate(totalTokens);
        CreditAccount account = requireLockedAccount(task.userId());
        long collectibleExtra = expectedCreditCost <= reservedCredit
                ? 0L
                : Math.min(account.balance(), expectedCreditCost - reservedCredit);
        long actualCreditCost = safeAdd(reservedCredit, collectibleExtra);
        if (expectedCreditCost < reservedCredit) {
            actualCreditCost = expectedCreditCost;
        }
        long settlementDelta = reservedCredit - actualCreditCost;
        long balanceAfter = safeAdd(account.balance(), settlementDelta);
        if (settlementDelta != 0) {
            persistenceService.updateBalance(task.userId(), balanceAfter);
        }

        persistenceService.appendTransaction(new NewCreditTransaction(
                task.userId(),
                task.tenantId(),
                settlementDelta,
                balanceAfter,
                UserCreditTransactionType.GENERATION_SETTLEMENT,
                task.taskId(),
                buildReservedGenerationRemark(
                        billingDecision, reservedCredit, expectedCreditCost, actualCreditCost),
                null,
                totalTokens
        ));
        persistenceService.settleGenerationTask(task.recordId(), actualCreditCost, totalTokens);
        creditMetricsCollector.recordReservationSettlement(reservedCredit, actualCreditCost);
        recordProviderCostSettlement(billingDecision);
        log.info(
                "生成任务积分预授权结算完成，taskId: {}, providerTokens: {}, "
                        + "billedTokens: {}, waivedTokens: {}, reservedCost: {}, "
                        + "expectedCost: {}, actualCost: {}, balanceAfter: {}",
                task.taskId(),
                billingDecision.providerObservedTokens(),
                billingDecision.chargeableTokens(),
                billingDecision.waivedTokens(),
                reservedCredit,
                expectedCreditCost,
                actualCreditCost,
                balanceAfter
        );
    }

    /** 处理{@code settle}{@code Legacy}生成任务。 */
    private void settleLegacyGenerationTask(GenerationCreditTask task) {
        UserBillingDecision billingDecision = requireBillingDecision(task.taskId());
        long totalTokens = billingDecision.chargeableTokens();
        long expectedCreditCost = costCalculator.calculate(totalTokens);
        CreditAccount account = requireLockedAccount(task.userId());
        long actualCreditCost = Math.min(account.balance(), expectedCreditCost);
        long balanceAfter = account.balance() - actualCreditCost;
        if (actualCreditCost > 0) {
            persistenceService.updateBalance(task.userId(), balanceAfter);
        }

        persistenceService.appendTransaction(new NewCreditTransaction(
                task.userId(),
                task.tenantId(),
                -actualCreditCost,
                balanceAfter,
                UserCreditTransactionType.GENERATION_CHARGE,
                task.taskId(),
                buildGenerationRemark(billingDecision, expectedCreditCost, actualCreditCost),
                null,
                totalTokens
        ));
        persistenceService.settleGenerationTask(task.recordId(), actualCreditCost, totalTokens);
        recordProviderCostSettlement(billingDecision);
        log.info(
                "生成任务积分结算完成，taskId: {}, providerTokens: {}, "
                        + "billedTokens: {}, waivedTokens: {}, expectedCost: {}, "
                        + "actualCost: {}, balanceAfter: {}",
                task.taskId(),
                billingDecision.providerObservedTokens(),
                billingDecision.chargeableTokens(),
                billingDecision.waivedTokens(),
                expectedCreditCost,
                actualCreditCost,
                balanceAfter
        );
    }

    private CreditAccount requireLockedAccount(Long userId) {
        CreditAccount account = persistenceService.lockActiveAccount(userId);
        if (account == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户积分账户不存在");
        }
        return account;
    }

    /**
     * 结算只消费完整的 Provider 成本快照。usage 估算与持久化尚未恢复时，保留任务的
     * 未结算状态和已预留积分，由 reconciler 幂等重试，不会将“未知”解释为零成本。
     */
    private UserBillingDecision requireBillingDecision(String taskId) {
        ProviderCostObservation observation =
                persistenceService.loadTaskProviderCostObservation(taskId);
        if (observation == null || observation.hasPendingAttempts()) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "生成任务 Provider 成本尚未完整，积分结算已保持待处理"
            );
        }
        try {
            return billingPolicy.decide(observation);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "生成任务用户计费决策失败",
                    failure
            );
        }
    }

    private void recordProviderCostSettlement(UserBillingDecision decision) {
        creditMetricsCollector.recordProviderCostSettlement(
                decision.providerObservedTokens(),
                decision.chargeableTokens(),
                decision.waivedTokens()
        );
    }

    /** 恢复生成{@code Settlement}。 */
    private void recoverGenerationSettlement(GenerationCreditTask task, CreditTransaction transaction) {
        if (transaction.userId() != task.userId()
                || !Objects.equals(transaction.tenantId(), task.tenantId())
                || transaction.type() != UserCreditTransactionType.GENERATION_CHARGE
                || !Objects.equals(transaction.bizId(), task.taskId())
                || transaction.changeAmount() > 0
                || transaction.tokenCount() == null
                || transaction.tokenCount() < 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成任务积分流水与任务数据不一致");
        }
        final long actualCreditCost;
        try {
            actualCreditCost = Math.negateExact(transaction.changeAmount());
        } catch (ArithmeticException exception) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "生成任务积分流水扣费金额不合法",
                    exception
            );
        }
        persistenceService.settleGenerationTask(
                task.recordId(),
                actualCreditCost,
                transaction.tokenCount()
        );
        log.warn("已根据积分流水恢复生成任务结算状态，taskId: {}", task.taskId());
    }

    /** 恢复{@code Reserved}生成{@code Settlement}。 */
    private void recoverReservedGenerationSettlement(GenerationCreditTask task,
                                                     CreditTransaction settlement) {
        CreditTransaction reservation = persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION,
                task.taskId()
        );
        if (reservation == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成任务结算流水缺少对应预授权流水");
        }
        long reservedCredit = validateReservation(task, reservation);
        if (settlement.userId() != task.userId()
                || !Objects.equals(settlement.tenantId(), task.tenantId())
                || settlement.type() != UserCreditTransactionType.GENERATION_SETTLEMENT
                || !Objects.equals(settlement.bizId(), task.taskId())
                || settlement.adminUserId() != null
                || settlement.tokenCount() == null
                || settlement.tokenCount() < 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成任务预授权结算流水与任务数据不一致");
        }
        final long actualCreditCost;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            actualCreditCost = Math.subtractExact(reservedCredit, settlement.changeAmount());
        } catch (ArithmeticException overflow) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成任务预授权结算金额不合法", overflow);
        }
        if (actualCreditCost < 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成任务预授权结算金额不能小于 0");
        }
        persistenceService.settleGenerationTask(
                task.recordId(), actualCreditCost, settlement.tokenCount());
        log.warn("已根据预授权结算流水恢复生成任务结算状态，taskId: {}", task.taskId());
    }

    /** 校验{@code ate}{@code Reservation}是否有效。 */
    private long validateReservation(GenerationCreditTask task, CreditTransaction reservation) {
        if (reservation.userId() != task.userId()
                || !Objects.equals(reservation.tenantId(), task.tenantId())
                || reservation.type() != UserCreditTransactionType.GENERATION_RESERVATION
                || !Objects.equals(reservation.bizId(), task.taskId())
                || reservation.changeAmount() >= 0
                || reservation.adminUserId() != null
                || reservation.tokenCount() != null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成任务积分预授权流水与任务数据不一致");
        }
        try {
            return Math.negateExact(reservation.changeAmount());
        } catch (ArithmeticException overflow) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成任务积分预授权金额不合法", overflow);
        }
    }

    private void validateExistingReservation(CreditTransaction transaction,
                                             GenerationCreditReservationCommand command,
                                             String pricingReference) {
        if (transaction.userId() != command.userId()
                || !Objects.equals(transaction.tenantId(), command.tenantId())
                || transaction.changeAmount() != -command.reservedCredit()
                || transaction.type() != UserCreditTransactionType.GENERATION_RESERVATION
                || !Objects.equals(transaction.bizId(), command.taskId().trim())
                || !Objects.equals(transaction.remark(), reservationRemark(pricingReference))
                || transaction.adminUserId() != null
                || transaction.tokenCount() != null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成任务积分预授权请求与已有流水冲突");
        }
    }

    private void validateExistingInitialization(CreditTransaction transaction,
                                                  Long userId,
                                                  Long initialCredit,
                                                  Long adminUserId) {
        if (transaction.userId() != userId
                || transaction.changeAmount() != initialCredit
                || transaction.type() != UserCreditTransactionType.ACCOUNT_INITIALIZATION
                || !Objects.equals(transaction.bizId(), String.valueOf(userId))
                || !Objects.equals(transaction.remark(), ACCOUNT_INITIALIZATION_REMARK)
                || !Objects.equals(transaction.adminUserId(), adminUserId)
                || transaction.tokenCount() != null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "用户积分初始化流水存在冲突");
        }
    }

    private void validateExistingAdjustment(CreditTransaction transaction,
                                             ValidatedAdjustment adjustment) {
        if (transaction.userId() != adjustment.userId()
                || transaction.changeAmount() != adjustment.changeAmount()
                || transaction.type() != UserCreditTransactionType.ADMIN_ADJUST
                || !Objects.equals(transaction.bizId(), adjustment.requestId())
                || !Objects.equals(transaction.remark(), adjustment.remark())
                || !Objects.equals(transaction.adminUserId(), adjustment.adminUserId())
                || transaction.tokenCount() != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "requestId 已被其他积分调整请求使用");
        }
    }

    /** 校验{@code ate}{@code Adjustment}是否有效。 */
    private ValidatedAdjustment validateAdjustment(AdminCreditAdjustmentCommand command) {
        if (command == null || !hasPositiveId(command.userId())
                || command.changeAmount() == null || command.changeAmount() == 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "积分调整参数不合法");
        }
        requireAdmin(command.adminUserId());
        String remark = normalizeRemark(command.remark());
        String requestId = normalizeRequestId(command.requestId());
        return new ValidatedAdjustment(
                requestId,
                command.userId(),
                command.changeAmount(),
                remark,
                command.adminUserId()
        );
    }

    /** 规范化请求编号。 */
    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "requestId 不能为空");
        }
        String candidate = requestId.trim();
        final UUID uuid;
        try {
            uuid = UUID.fromString(candidate);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "requestId 必须是标准 UUID", exception);
        }
        if (!uuid.toString().equalsIgnoreCase(candidate)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "requestId 必须是标准 UUID");
        }
        return uuid.toString();
    }

    /** 规范化{@code Remark}。 */
    private String normalizeRemark(String remark) {
        if (remark == null || remark.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "积分调整原因不能为空");
        }
        String normalized = remark.trim();
        if (normalized.length() > MAX_REMARK_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "积分调整原因不能超过 512 个字符");
        }
        return normalized;
    }

    /** 规范化{@code Pricing}{@code Reference}。 */
    private String normalizePricingReference(String pricingReference) {
        if (pricingReference == null || pricingReference.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "积分预授权计价引用不能为空");
        }
        String normalized = pricingReference.trim();
        if (normalized.length() > MAX_REMARK_LENGTH - "reservation:".length()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "积分预授权计价引用过长");
        }
        return normalized;
    }

    /** 校验并返回有效的任务编号。 */
    private String requireTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成任务 ID 不能为空");
        }
        String normalized = taskId.trim();
        if (normalized.length() > 128) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成任务 ID 长度不合法");
        }
        return normalized;
    }

    private void requireAdmin(Long adminUserId) {
        if (!hasPositiveId(adminUserId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "缺少有效的管理员身份");
        }
    }

    /** 计算本次额度变更后的账户余额。 */
    private long calculateBalanceAfter(long currentBalance, long changeAmount) {
        final long balanceAfter;
        try {
            balanceAfter = Math.addExact(currentBalance, changeAmount);
        } catch (ArithmeticException exception) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "积分调整结果超出允许范围",
                    exception
            );
        }
        if (balanceAfter < 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "积分不足");
        }
        return balanceAfter;
    }

    /** 返回安全{@code Add}。 */
    private long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "积分结算金额超出允许范围", overflow);
        }
    }

    private String reservationRemark(String pricingReference) {
        return "reservation:" + pricingReference;
    }

    private String buildReservedGenerationRemark(UserBillingDecision decision,
                                                 long reservedCredit,
                                                 long expectedCreditCost,
                                                 long actualCreditCost) {
        return "AI generation settlement: " + billingAuditSummary(decision)
                + ", reserved=" + reservedCredit
                + ", expected=" + expectedCreditCost
                + ", captured=" + actualCreditCost;
    }

    private String buildGenerationRemark(UserBillingDecision decision,
                                         long expectedCreditCost,
                                         long actualCreditCost) {
        return "AI generation charge: " + billingAuditSummary(decision)
                + ", expected=" + expectedCreditCost
                + ", captured=" + actualCreditCost;
    }

    private String billingAuditSummary(UserBillingDecision decision) {
        return "billedTokens=" + decision.chargeableTokens()
                + ", providerTokens=" + decision.providerObservedTokens()
                + ", waivedTokens=" + decision.waivedTokens()
                + ", policy=" + decision.policyReference();
    }

    private boolean hasPositiveId(Long value) {
        return value != null && value > 0;
    }

    private record ValidatedAdjustment(
            String requestId,
            long userId,
            long changeAmount,
            String remark,
            long adminUserId
    ) {
    }
}
