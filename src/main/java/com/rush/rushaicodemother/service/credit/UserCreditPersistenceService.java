package com.rush.rushaicodemother.service.credit;

import com.rush.rushaicodemother.model.enums.UserCreditTransactionType;

import java.time.LocalDateTime;
import java.util.List;

/** 用户积分模块的显式持久化边界。 */
public interface UserCreditPersistenceService {

    CreditAccount findActiveAccount(Long userId);

    CreditAccount lockActiveAccount(Long userId);

    GenerationCreditTask lockGenerationTask(String taskId);

    GenerationCreditTask findGenerationTask(String taskId);

    CreditTransaction findTransaction(UserCreditTransactionType type, String bizId);

    ProviderCostObservation loadTaskProviderCostObservation(String taskId);

    void updateBalance(Long userId, long balanceAfter);

    void appendTransaction(NewCreditTransaction transaction);

    void settleGenerationTask(Long taskRecordId, long creditCost, long totalTokens);

    List<String> findUnsettledTerminalTaskIds(int limit);

    List<String> findRecoverablePreflightReservationTaskIds(
            LocalDateTime createdBefore, int limit);

    record CreditAccount(long userId, long balance) {
    }

    record GenerationCreditTask(long recordId,
                                String taskId,
                                long userId,
                                Long tenantId,
                                Long appId,
                                boolean settled,
                                Long creditCost,
                                Long totalTokens) {

        /** 兼容尚未读取应用身份的任务投影。 */
        public GenerationCreditTask(long recordId,
                                    String taskId,
                                    long userId,
                                    Long tenantId,
                                    boolean settled,
                                    Long creditCost,
                                    Long totalTokens) {
            this(recordId, taskId, userId, tenantId, null, settled, creditCost, totalTokens);
        }

        /** 兼容不读取结算明细的旧调用方。 */
        public GenerationCreditTask(long recordId,
                                    String taskId,
                                    long userId,
                                    Long tenantId,
                                    boolean settled) {
            this(recordId, taskId, userId, tenantId, null, settled, null, null);
        }

        /** 兼容旧测试和迁移前任务投影。 */
        public GenerationCreditTask(long recordId, String taskId, long userId, boolean settled) {
            this(recordId, taskId, userId, null, null, settled, null, null);
        }
    }

    record CreditTransaction(
            long userId,
            Long tenantId,
            Long appId,
            long changeAmount,
            long balanceAfter,
            UserCreditTransactionType type,
            String bizId,
            String remark,
            Long adminUserId,
            Long tokenCount
    ) {
        /** 兼容尚未携带应用身份的流水投影。 */
        public CreditTransaction(long userId,
                                 Long tenantId,
                                 long changeAmount,
                                 long balanceAfter,
                                 UserCreditTransactionType type,
                                 String bizId,
                                 String remark,
                                 Long adminUserId,
                                 Long tokenCount) {
            this(userId, tenantId, null, changeAmount, balanceAfter, type, bizId,
                    remark, adminUserId, tokenCount);
        }

        /** 兼容迁移前流水投影。 */
        public CreditTransaction(long userId,
                                 long changeAmount,
                                 long balanceAfter,
                                 UserCreditTransactionType type,
                                 String bizId,
                                 String remark,
                                 Long adminUserId,
                                 Long tokenCount) {
            this(userId, null, null, changeAmount, balanceAfter, type, bizId,
                    remark, adminUserId, tokenCount);
        }
    }

    record NewCreditTransaction(
            long userId,
            Long tenantId,
            Long appId,
            long changeAmount,
            long balanceAfter,
            UserCreditTransactionType type,
            String bizId,
            String remark,
            Long adminUserId,
            Long tokenCount
    ) {
        /** 兼容尚未携带应用身份的生成流水写入。 */
        public NewCreditTransaction(long userId,
                                    Long tenantId,
                                    long changeAmount,
                                    long balanceAfter,
                                    UserCreditTransactionType type,
                                    String bizId,
                                    String remark,
                                    Long adminUserId,
                                    Long tokenCount) {
            this(userId, tenantId, null, changeAmount, balanceAfter, type, bizId,
                    remark, adminUserId, tokenCount);
        }

        /** 兼容不属于生成任务的旧积分写入。 */
        public NewCreditTransaction(long userId,
                                    long changeAmount,
                                    long balanceAfter,
                                    UserCreditTransactionType type,
                                    String bizId,
                                    String remark,
                                    Long adminUserId,
                                    Long tokenCount) {
            this(userId, null, null, changeAmount, balanceAfter, type, bizId,
                    remark, adminUserId, tokenCount);
        }
    }
}
