package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.UserCreditTransactionType;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.credit.AdminCreditAdjustmentCommand;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class UserCreditServiceImpl implements UserCreditService {

    private static final int MAX_REMARK_LENGTH = 512;
    private static final String ACCOUNT_INITIALIZATION_REMARK = "管理员创建用户初始化积分";

    private final UserCreditPersistenceService persistenceService;
    private final UserCreditCostCalculator costCalculator;

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

        CreditTransaction existing = persistenceService.findTransaction(
                UserCreditTransactionType.GENERATION_CHARGE,
                normalizedTaskId
        );
        if (existing != null) {
            recoverGenerationSettlement(task, existing);
            return;
        }

        long totalTokens = persistenceService.sumPositiveTaskTokens(normalizedTaskId);
        long expectedCreditCost = costCalculator.calculate(totalTokens);
        CreditAccount account = requireLockedAccount(task.userId());
        long actualCreditCost = Math.min(account.balance(), expectedCreditCost);
        long balanceAfter = account.balance() - actualCreditCost;
        if (actualCreditCost > 0) {
            persistenceService.updateBalance(task.userId(), balanceAfter);
        }

        persistenceService.appendTransaction(new NewCreditTransaction(
                task.userId(),
                -actualCreditCost,
                balanceAfter,
                UserCreditTransactionType.GENERATION_CHARGE,
                normalizedTaskId,
                buildGenerationRemark(totalTokens, expectedCreditCost, actualCreditCost),
                null,
                totalTokens
        ));
        persistenceService.settleGenerationTask(task.recordId(), actualCreditCost, totalTokens);
        log.info(
                "生成任务积分结算完成，taskId: {}, tokens: {}, expectedCost: {}, actualCost: {}, balanceAfter: {}",
                normalizedTaskId,
                totalTokens,
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

    private void recoverGenerationSettlement(GenerationCreditTask task, CreditTransaction transaction) {
        if (transaction.userId() != task.userId()
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

    private String buildGenerationRemark(long totalTokens,
                                         long expectedCreditCost,
                                         long actualCreditCost) {
        if (actualCreditCost < expectedCreditCost) {
            return "AI 生成消耗 " + totalTokens + " token，应扣 " + expectedCreditCost
                    + " 积分，余额不足实际扣除 " + actualCreditCost + " 积分";
        }
        return "AI 生成消耗 " + totalTokens + " token，扣除 " + actualCreditCost + " 积分";
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
