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
    private static final String TASK_RESERVATION_REMARK_PREFIX = "reservation:";
    private static final String PREFLIGHT_RESERVATION_REMARK_PREFIX = "reservation:preflight:";

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
        ensureHasCredit(userId, 1L);
    }

    @Override
    public void ensureHasCredit(Long userId, long requiredCredit) {
        if (requiredCredit <= 0) {
            throw new IllegalArgumentException("所需积分必须大于 0");
        }
        if (!hasPositiveId(userId)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        CreditAccount account = persistenceService.findActiveAccount(userId);
        if (account == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        if (account.balance() < requiredCredit) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "积分不足，本任务最多需要 " + requiredCredit + " 积分");
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
        reserveGeneration(command, ReservationPhase.TASK);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reserveGenerationPreflight(GenerationCreditReservationCommand command) {
        reserveGeneration(command, ReservationPhase.PREFLIGHT);
    }

    private void reserveGeneration(GenerationCreditReservationCommand command,
                                   ReservationPhase phase) {
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
            validateExistingReservation(existing, command, taskId, pricingReference, phase);
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
                reservationRemark(phase, pricingReference),
                null,
                null
        ));
    }

    /**
     * 回收没有形成正式任务的预检预授权。先按任务锁、再按账户锁复核，避免与正式准入并发时
     * 把已经被任务接管的额度误退回。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void settleGenerationPreflight(String taskId) {
        String normalizedTaskId = requireTaskId(taskId);
        if (persistenceService.lockGenerationTask(normalizedTaskId) != null) {
            return;
        }
        CreditTransaction reservation = persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_RESERVATION, normalizedTaskId);
        if (reservation == null) {
            return;
        }
        long reservedCredit = validatePreflightReservation(normalizedTaskId, reservation);
        CreditTransaction existingSettlement = persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_SETTLEMENT, normalizedTaskId);
        if (existingSettlement != null) {
            validatePreflightSettlement(reservation, existingSettlement, reservedCredit);
            return;
        }

        UserBillingDecision billingDecision = requireBillingDecision(normalizedTaskId);
        long totalTokens = billingDecision.chargeableTokens();
        CreditAccount account = requireLockedAccount(reservation.userId());

        // 账户锁与正式预授权写入共用串行化点；加锁后必须再次确认任务和结算状态。
        if (persistenceService.findGenerationTask(normalizedTaskId) != null) {
            return;
        }
        existingSettlement = persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_SETTLEMENT, normalizedTaskId);
        if (existingSettlement != null) {
            validatePreflightSettlement(reservation, existingSettlement, reservedCredit);
            return;
        }

        ReservedCreditSettlement settlement = calculateReservedSettlement(
                reservedCredit, billingDecision, account);
        if (settlement.balanceDelta() != 0) {
            persistenceService.updateBalance(reservation.userId(), settlement.balanceAfter());
        }
        persistenceService.appendTransaction(new NewCreditTransaction(
                reservation.userId(),
                reservation.tenantId(),
                settlement.balanceDelta(),
                settlement.balanceAfter(),
                UserCreditTransactionType.GENERATION_SETTLEMENT,
                normalizedTaskId,
                buildPreflightSettlementRemark(
                        billingDecision, reservedCredit,
                        settlement.expectedCreditCost(), settlement.actualCreditCost()),
                null,
                totalTokens
        ));
        creditMetricsCollector.recordReservationSettlement(
                reservedCredit, settlement.actualCreditCost());
        recordProviderCostSettlement(billingDecision);
        log.info("模型预检积分结算完成，taskId: {}, providerTokens: {}, actualCost: {}, balanceAfter: {}",
                normalizedTaskId, billingDecision.providerObservedTokens(),
                settlement.actualCreditCost(), settlement.balanceAfter());
    }

    /** 处理{@code settle}{@code Reserved}生成任务。 */
    private void settleReservedGenerationTask(GenerationCreditTask task,
                                              CreditTransaction reservation) {
        long reservedCredit = validateReservation(task, reservation);
        UserBillingDecision billingDecision = requireBillingDecision(task.taskId());
        long totalTokens = billingDecision.chargeableTokens();
        CreditAccount account = requireLockedAccount(task.userId());
        ReservedCreditSettlement settlement = calculateReservedSettlement(
                reservedCredit, billingDecision, account);
        if (settlement.balanceDelta() != 0) {
            persistenceService.updateBalance(task.userId(), settlement.balanceAfter());
        }

        persistenceService.appendTransaction(new NewCreditTransaction(
                task.userId(),
                task.tenantId(),
                settlement.balanceDelta(),
                settlement.balanceAfter(),
                UserCreditTransactionType.GENERATION_SETTLEMENT,
                task.taskId(),
                buildReservedGenerationRemark(
                        billingDecision, reservedCredit,
                        settlement.expectedCreditCost(), settlement.actualCreditCost()),
                null,
                totalTokens
        ));
        persistenceService.settleGenerationTask(
                task.recordId(), settlement.actualCreditCost(), totalTokens);
        creditMetricsCollector.recordReservationSettlement(
                reservedCredit, settlement.actualCreditCost());
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
                settlement.expectedCreditCost(),
                settlement.actualCreditCost(),
                settlement.balanceAfter()
        );
    }

    /** 统一任务结算和孤儿预检结算的预授权捕获/退款算法。 */
    private ReservedCreditSettlement calculateReservedSettlement(
            long reservedCredit,
            UserBillingDecision billingDecision,
            CreditAccount account) {
        long expectedCreditCost = costCalculator.calculate(billingDecision.chargeableTokens());
        long collectibleExtra = expectedCreditCost <= reservedCredit
                ? 0L
                : Math.min(account.balance(), expectedCreditCost - reservedCredit);
        long actualCreditCost = expectedCreditCost < reservedCredit
                ? expectedCreditCost
                : safeAdd(reservedCredit, collectibleExtra);
        long balanceDelta = reservedCredit - actualCreditCost;
        long balanceAfter = safeAdd(account.balance(), balanceDelta);
        return new ReservedCreditSettlement(
                expectedCreditCost, actualCreditCost, balanceDelta, balanceAfter);
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
                                             String taskId,
                                             String pricingReference,
                                             ReservationPhase phase) {
        boolean invalidIdentity = transaction.userId() != command.userId()
                || !Objects.equals(transaction.tenantId(), command.tenantId())
                || transaction.type() != UserCreditTransactionType.GENERATION_RESERVATION
                || !Objects.equals(transaction.bizId(), taskId)
                || transaction.changeAmount() >= 0
                || transaction.adminUserId() != null
                || transaction.tokenCount() != null;
        if (invalidIdentity) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成任务积分预授权请求与已有流水冲突");
        }
        long existingReservedCredit = negateReservationAmount(transaction.changeAmount());
        boolean exactPhaseMatch = existingReservedCredit == command.reservedCredit()
                && Objects.equals(transaction.remark(), reservationRemark(phase, pricingReference));
        boolean taskAdoptsPreflight = phase == ReservationPhase.TASK
                && isPreflightReservation(transaction)
                && existingReservedCredit >= command.reservedCredit();
        if (!exactPhaseMatch && !taskAdoptsPreflight) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成任务积分预授权请求与已有流水冲突");
        }
        if (isPreflightReservation(transaction)
                && persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_SETTLEMENT, taskId) != null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型预检额度已经结算，不能再次接管");
        }
    }

    private long validatePreflightReservation(String taskId, CreditTransaction reservation) {
        if (reservation.type() != UserCreditTransactionType.GENERATION_RESERVATION
                || !Objects.equals(reservation.bizId(), taskId)
                || !hasPositiveId(reservation.userId())
                || !hasPositiveId(reservation.tenantId())
                || reservation.adminUserId() != null
                || reservation.tokenCount() != null
                || !isPreflightReservation(reservation)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型预检积分预授权流水不合法");
        }
        return negateReservationAmount(reservation.changeAmount());
    }

    private void validatePreflightSettlement(CreditTransaction reservation,
                                             CreditTransaction settlement,
                                             long reservedCredit) {
        if (settlement.userId() != reservation.userId()
                || !Objects.equals(settlement.tenantId(), reservation.tenantId())
                || settlement.type() != UserCreditTransactionType.GENERATION_SETTLEMENT
                || !Objects.equals(settlement.bizId(), reservation.bizId())
                || settlement.adminUserId() != null
                || settlement.tokenCount() == null
                || settlement.tokenCount() < 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型预检积分结算流水不合法");
        }
        try {
            if (Math.subtractExact(reservedCredit, settlement.changeAmount()) < 0) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型预检积分结算金额不合法");
            }
        } catch (ArithmeticException overflow) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "模型预检积分结算金额不合法", overflow);
        }
    }

    private long negateReservationAmount(long changeAmount) {
        if (changeAmount >= 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成任务积分预授权金额不合法");
        }
        try {
            return Math.negateExact(changeAmount);
        } catch (ArithmeticException overflow) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成任务积分预授权金额不合法", overflow);
        }
    }

    private boolean isPreflightReservation(CreditTransaction transaction) {
        return transaction.remark() != null
                && transaction.remark().startsWith(PREFLIGHT_RESERVATION_REMARK_PREFIX);
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
        if (normalized.length() > MAX_REMARK_LENGTH - PREFLIGHT_RESERVATION_REMARK_PREFIX.length()) {
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

    private String reservationRemark(ReservationPhase phase, String pricingReference) {
        return (phase == ReservationPhase.PREFLIGHT
                ? PREFLIGHT_RESERVATION_REMARK_PREFIX
                : TASK_RESERVATION_REMARK_PREFIX) + pricingReference;
    }

    private String buildPreflightSettlementRemark(UserBillingDecision decision,
                                                  long reservedCredit,
                                                  long expectedCreditCost,
                                                  long actualCreditCost) {
        return "AI preflight settlement: " + billingAuditSummary(decision)
                + ", reserved=" + reservedCredit
                + ", expected=" + expectedCreditCost
                + ", captured=" + actualCreditCost;
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

    private enum ReservationPhase {
        PREFLIGHT,
        TASK
    }

    private record ReservedCreditSettlement(
            long expectedCreditCost,
            long actualCreditCost,
            long balanceDelta,
            long balanceAfter
    ) {
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
