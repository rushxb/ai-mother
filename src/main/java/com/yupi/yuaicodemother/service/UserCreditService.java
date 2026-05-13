package com.yupi.yuaicodemother.service;

public interface UserCreditService {

    long TOKENS_PER_CREDIT = 100_000L;

    long calculateCreditCost(long totalTokens);

    long adjustCredit(Long userId, Long changeAmount, String type, String bizId, String remark, Long adminUserId, Long tokenCount);

    void chargeGenerationTask(String taskId);
}
