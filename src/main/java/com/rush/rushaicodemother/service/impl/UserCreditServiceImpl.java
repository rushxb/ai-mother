package com.rush.rushaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.GenerationModelCallMapper;
import com.rush.rushaicodemother.mapper.GenerationTaskMapper;
import com.rush.rushaicodemother.mapper.UserCreditTransactionMapper;
import com.rush.rushaicodemother.model.entity.GenerationModelCall;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.entity.UserCreditTransaction;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class UserCreditServiceImpl implements UserCreditService {

    private static final String TYPE_ADMIN_ADJUST = "ADMIN_ADJUST";
    private static final String TYPE_GENERATION_CHARGE = "GENERATION_CHARGE";

    @Resource
    private UserService userService;

    @Resource
    private UserCreditTransactionMapper userCreditTransactionMapper;

    @Resource
    private GenerationTaskMapper generationTaskMapper;

    @Resource
    private GenerationModelCallMapper generationModelCallMapper;

    @Override
    public long calculateCreditCost(long totalTokens) {
        if (totalTokens <= 0) {
            return 0L;
        }
        return (totalTokens + TOKENS_PER_CREDIT - 1) / TOKENS_PER_CREDIT;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long adjustCredit(Long userId, Long changeAmount, String type, String bizId, String remark, Long adminUserId, Long tokenCount) {
        if (userId == null || userId <= 0 || changeAmount == null || changeAmount == 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }
        long currentBalance = normalizeBalance(user.getCreditBalance());
        long balanceAfter = currentBalance + changeAmount;
        if (balanceAfter < 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "积分不足");
        }
        User updateUser = new User();
        updateUser.setId(userId);
        updateUser.setCreditBalance(balanceAfter);
        boolean updated = userService.updateById(updateUser);
        if (!updated) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "积分更新失败");
        }
        UserCreditTransaction transaction = UserCreditTransaction.builder()
                .userId(userId)
                .changeAmount(changeAmount)
                .balanceAfter(balanceAfter)
                .type(StrUtil.blankToDefault(type, TYPE_ADMIN_ADJUST))
                .bizId(bizId)
                .remark(remark)
                .adminUserId(adminUserId)
                .tokenCount(tokenCount)
                .build();
        userCreditTransactionMapper.insert(transaction);
        return balanceAfter;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void chargeGenerationTask(String taskId) {
        if (StrUtil.isBlank(taskId) || hasCharged(taskId)) {
            return;
        }
        GenerationTask task = generationTaskMapper.selectOneByQuery(QueryWrapper.create()
                .eq(GenerationTask::getTaskId, taskId)
                .limit(1));
        if (task == null || task.getUserId() == null) {
            return;
        }
        long totalTokens = sumTaskTokens(taskId);
        long creditCost = calculateCreditCost(totalTokens);
        if (creditCost <= 0) {
            markTaskCharged(task, 0L, 0L);
            return;
        }
        User user = userService.getById(task.getUserId());
        if (user == null) {
            return;
        }
        long currentBalance = normalizeBalance(user.getCreditBalance());
        long actualCost = Math.min(currentBalance, creditCost);
        if (actualCost <= 0) {
            markTaskCharged(task, 0L, totalTokens);
            log.info("生成任务积分不足未扣费，taskId: {}, tokens: {}, creditCost: {}", taskId, totalTokens, creditCost);
            return;
        }
        User updateUser = new User();
        updateUser.setId(task.getUserId());
        updateUser.setCreditBalance(currentBalance - actualCost);
        boolean updated = userService.updateById(updateUser);
        if (!updated) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "积分更新失败");
        }
        UserCreditTransaction transaction = UserCreditTransaction.builder()
                .userId(task.getUserId())
                .changeAmount(-actualCost)
                .balanceAfter(currentBalance - actualCost)
                .type(TYPE_GENERATION_CHARGE)
                .bizId(taskId)
                .remark("AI 生成消耗 " + totalTokens + " token，应扣 " + creditCost + " 积分")
                .tokenCount(totalTokens)
                .build();
        userCreditTransactionMapper.insert(transaction);
        markTaskCharged(task, actualCost, totalTokens);
        log.info("生成任务积分扣费完成，taskId: {}, tokens: {}, creditCost: {}, actualCost: {}, balanceAfter: {}", taskId, totalTokens, creditCost, actualCost, currentBalance - actualCost);
    }

    private boolean hasCharged(String taskId) {
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq(UserCreditTransaction::getType, TYPE_GENERATION_CHARGE)
                .eq(UserCreditTransaction::getBizId, taskId)
                .limit(1);
        return userCreditTransactionMapper.selectCountByQuery(queryWrapper) > 0;
    }

    private long sumTaskTokens(String taskId) {
        List<GenerationModelCall> modelCalls = generationModelCallMapper.selectListByQuery(QueryWrapper.create()
                .eq(GenerationModelCall::getTaskId, taskId));
        return modelCalls.stream()
                .map(GenerationModelCall::getTotalTokens)
                .filter(value -> value != null && value > 0)
                .mapToLong(Integer::longValue)
                .sum();
    }

    private void markTaskCharged(GenerationTask task, Long creditCost, Long totalTokens) {
        task.setCreditCharged(1);
        task.setCreditCost(creditCost);
        task.setTotalTokens(totalTokens);
        generationTaskMapper.update(task);
    }

    private long normalizeBalance(Long creditBalance) {
        return creditBalance == null ? 0L : creditBalance;
    }
}
