package com.rush.rushaicodemother.service.credit;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.UserCreditMapper;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.entity.UserCreditTransaction;
import com.rush.rushaicodemother.model.enums.UserCreditTransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 用户积分持久化边界的 MyBatis 实现。 */
@Repository
@RequiredArgsConstructor
public class DefaultUserCreditPersistenceService implements UserCreditPersistenceService {

    private static final int MAX_BIZ_ID_LENGTH = 128;
    private static final int MAX_REMARK_LENGTH = 512;

    private final UserCreditMapper mapper;

    /**
 * 查找匹配的活动{@code Account}。
 *
 * @param userId 用户编号
 * @return 活动{@code Account}
 */
    @Override
    public CreditAccount findActiveAccount(Long userId) {
        requirePositiveId(userId, "用户 ID");
        return toCreditAccount(mapper.selectActiveCreditAccount(userId));
    }

    /**
 * 返回锁活动{@code Account}。
 *
 * @param userId 用户编号
 * @return 默认用户额度持久化
 */
    @Override
    public CreditAccount lockActiveAccount(Long userId) {
        requirePositiveId(userId, "用户 ID");
        return toCreditAccount(mapper.selectActiveCreditAccountForUpdate(userId));
    }

    /**
 * 返回锁生成任务。
 *
 * @param taskId 任务编号
 * @return 默认用户额度持久化
 */
    @Override
    public GenerationCreditTask lockGenerationTask(String taskId) {
        String normalizedTaskId = requireBusinessId(taskId, "生成任务 ID");
        GenerationTask task = mapper.selectGenerationTaskForUpdate(normalizedTaskId);
        if (task == null) {
            return null;
        }
        if (task.getId() == null || task.getId() <= 0
                || task.getUserId() == null || task.getUserId() <= 0
                || task.getTaskId() == null || task.getTaskId().isBlank()) {
            throw corruptedData("生成任务积分结算数据不完整");
        }
        return new GenerationCreditTask(
                task.getId(),
                task.getTaskId(),
                task.getUserId(),
                Integer.valueOf(1).equals(task.getCreditCharged())
        );
    }

    /**
 * 查找匹配的事务。
 *
 * @param type 目标类型
 * @param bizId 目标资源编号
 * @return 事务
 */
    @Override
    public CreditTransaction findTransaction(UserCreditTransactionType type, String bizId) {
        if (type == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "积分流水类型不能为空");
        }
        String normalizedBizId = requireBusinessId(bizId, "积分流水业务 ID");
        UserCreditTransaction transaction = mapper.selectTransactionByTypeAndBizId(
                type.name(),
                normalizedBizId
        );
        return transaction == null ? null : toCreditTransaction(transaction);
    }

    /**
 * 计算正数任务令牌的汇总值。
 *
 * @param taskId 任务编号
 * @return 计算或处理后的数值结果
 */
    @Override
    public long sumPositiveTaskTokens(String taskId) {
        String normalizedTaskId = requireBusinessId(taskId, "生成任务 ID");
        Long totalTokens = mapper.sumPositiveTaskTokens(normalizedTaskId);
        if (totalTokens == null) {
            return 0L;
        }
        if (totalTokens < 0) {
            throw corruptedData("生成任务 token 汇总结果不合法");
        }
        return totalTokens;
    }

    /**
 * 更新余额。
 *
 * @param userId 用户编号
 * @param balanceAfter 余额执行后
 */
    @Override
    public void updateBalance(Long userId, long balanceAfter) {
        requirePositiveId(userId, "用户 ID");
        if (balanceAfter < 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "积分余额不能小于 0");
        }
        if (mapper.updateCreditBalance(userId, balanceAfter) != 1) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "积分余额更新失败");
        }
    }

    /**
 * 追加事务。
 *
 * @param transaction 事务
 */
    @Override
    public void appendTransaction(NewCreditTransaction transaction) {
        validateNewTransaction(transaction);
        UserCreditTransaction entity = UserCreditTransaction.builder()
                .userId(transaction.userId())
                .changeAmount(transaction.changeAmount())
                .balanceAfter(transaction.balanceAfter())
                .type(transaction.type().name())
                .bizId(transaction.bizId())
                .remark(transaction.remark())
                .adminUserId(transaction.adminUserId())
                .tokenCount(transaction.tokenCount())
                .build();
        try {
            if (mapper.insertTransaction(entity) != 1) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "积分流水记录失败");
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "积分流水业务标识冲突，请勿重复提交",
                    exception
            );
        }
    }

    /**
 * 处理{@code settle}生成任务。
 *
 * @param taskRecordId 任务记录编号
 * @param creditCost {@code creditCost} 对应的调用参数
 * @param totalTokens 总量令牌
 */
    @Override
    public void settleGenerationTask(Long taskRecordId, long creditCost, long totalTokens) {
        requirePositiveId(taskRecordId, "生成任务记录 ID");
        if (creditCost < 0 || totalTokens < 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成任务积分结算参数不合法");
        }
        if (mapper.updateCreditSettlement(taskRecordId, creditCost, totalTokens) != 1) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成任务积分结算状态更新失败");
        }
    }

    /**
 * 查找匹配的{@code Unsettled}{@code Terminal}任务{@code Ids}。
 *
 * @param limit 资源上限
 * @return {@code Unsettled}{@code Terminal}任务{@code Ids}集合
 */
    @Override
    public List<String> findUnsettledTerminalTaskIds(int limit) {
        if (limit <= 0 || limit > 500) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "积分结算扫描批次必须在 1 到 500 之间");
        }
        List<String> taskIds = mapper.selectUnsettledTerminalTaskIds(limit);
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        for (String taskId : taskIds) {
            requireBusinessId(taskId, "生成任务 ID");
        }
        return List.copyOf(taskIds);
    }

    /** 将当前对象转换为额度{@code Account}。 */
    private CreditAccount toCreditAccount(User user) {
        if (user == null) {
            return null;
        }
        if (user.getId() == null || user.getId() <= 0
                || user.getCreditBalance() == null || user.getCreditBalance() < 0) {
            throw corruptedData("用户积分账户数据不完整");
        }
        return new CreditAccount(user.getId(), user.getCreditBalance());
    }

    /** 将当前对象转换为额度事务。 */
    private CreditTransaction toCreditTransaction(UserCreditTransaction transaction) {
        if (transaction.getUserId() == null || transaction.getUserId() <= 0
                || transaction.getChangeAmount() == null
                || transaction.getBalanceAfter() == null || transaction.getBalanceAfter() < 0
                || transaction.getType() == null || transaction.getBizId() == null) {
            throw corruptedData("用户积分流水数据不完整");
        }
        final UserCreditTransactionType type;
        try {
            type = UserCreditTransactionType.valueOf(transaction.getType());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "用户积分流水类型不合法",
                    exception
            );
        }
        return new CreditTransaction(
                transaction.getUserId(),
                transaction.getChangeAmount(),
                transaction.getBalanceAfter(),
                type,
                transaction.getBizId(),
                transaction.getRemark(),
                transaction.getAdminUserId(),
                transaction.getTokenCount()
        );
    }

    /** 校验{@code ate}{@code New}事务是否有效。 */
    private void validateNewTransaction(NewCreditTransaction transaction) {
        if (transaction == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "积分流水不能为空");
        }
        requirePositiveId(transaction.userId(), "用户 ID");
        if (transaction.balanceAfter() < 0 || transaction.type() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "积分流水参数不合法");
        }
        requireBusinessId(transaction.bizId(), "积分流水业务 ID");
        if (transaction.remark() == null || transaction.remark().isBlank()
                || transaction.remark().length() > MAX_REMARK_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "积分流水备注不合法");
        }
        switch (transaction.type()) {
            case ACCOUNT_INITIALIZATION -> validateAccountInitialization(transaction);
            case ADMIN_ADJUST -> validateAdminAdjustment(transaction);
            case GENERATION_CHARGE -> validateGenerationCharge(transaction);
            case GENERATION_RESERVATION -> validateGenerationReservation(transaction);
            case GENERATION_SETTLEMENT -> validateGenerationSettlement(transaction);
        }
    }

    private void validateAccountInitialization(NewCreditTransaction transaction) {
        if (transaction.changeAmount() <= 0 || !hasPositiveId(transaction.adminUserId())
                || transaction.tokenCount() != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "积分初始化流水参数不合法");
        }
    }

    private void validateAdminAdjustment(NewCreditTransaction transaction) {
        if (transaction.changeAmount() == 0 || !hasPositiveId(transaction.adminUserId())
                || transaction.tokenCount() != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "管理员积分调整流水参数不合法");
        }
    }

    private void validateGenerationCharge(NewCreditTransaction transaction) {
        if (transaction.changeAmount() > 0 || transaction.adminUserId() != null
                || transaction.tokenCount() == null || transaction.tokenCount() < 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成任务积分流水参数不合法");
        }
    }

    private void validateGenerationReservation(NewCreditTransaction transaction) {
        if (transaction.changeAmount() >= 0 || transaction.adminUserId() != null
                || transaction.tokenCount() != null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成任务积分预授权流水参数不合法");
        }
    }

    private void validateGenerationSettlement(NewCreditTransaction transaction) {
        if (transaction.adminUserId() != null
                || transaction.tokenCount() == null || transaction.tokenCount() < 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成任务积分结算流水参数不合法");
        }
    }

    private String requireBusinessId(String value, String fieldName) {
        if (value == null || value.isBlank() || value.length() > MAX_BIZ_ID_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, fieldName + "不合法");
        }
        return value;
    }

    private void requirePositiveId(Long value, String fieldName) {
        if (!hasPositiveId(value)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, fieldName + "不合法");
        }
    }

    private boolean hasPositiveId(Long value) {
        return value != null && value > 0;
    }

    private BusinessException corruptedData(String message) {
        return new BusinessException(ErrorCode.OPERATION_ERROR, message);
    }
}
