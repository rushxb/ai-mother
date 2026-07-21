package com.rush.rushaicodemother.service.credit;

import com.rush.rushaicodemother.model.enums.UserCreditTransactionType;

import java.util.List;

/** 用户积分模块的显式持久化边界。 */
public interface UserCreditPersistenceService {

    CreditAccount findActiveAccount(Long userId);

    CreditAccount lockActiveAccount(Long userId);

    GenerationCreditTask lockGenerationTask(String taskId);

    CreditTransaction findTransaction(UserCreditTransactionType type, String bizId);

    long sumPositiveTaskTokens(String taskId);

    void updateBalance(Long userId, long balanceAfter);

    void appendTransaction(NewCreditTransaction transaction);

    void settleGenerationTask(Long taskRecordId, long creditCost, long totalTokens);

    List<String> findUnsettledTerminalTaskIds(int limit);

    record CreditAccount(long userId, long balance) {
    }

    record GenerationCreditTask(long recordId, String taskId, long userId, boolean settled) {
    }

    record CreditTransaction(
            long userId,
            long changeAmount,
            long balanceAfter,
            UserCreditTransactionType type,
            String bizId,
            String remark,
            Long adminUserId,
            Long tokenCount
    ) {
    }

    record NewCreditTransaction(
            long userId,
            long changeAmount,
            long balanceAfter,
            UserCreditTransactionType type,
            String bizId,
            String remark,
            Long adminUserId,
            Long tokenCount
    ) {
    }
}
