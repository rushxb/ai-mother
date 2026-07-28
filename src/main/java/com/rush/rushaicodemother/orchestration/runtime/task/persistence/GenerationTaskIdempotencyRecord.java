package com.rush.rushaicodemother.orchestration.runtime.task.persistence;

import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSubmissionReceipt;

import java.util.Objects;

/** 解决幂等重试所需的持久提交身份与原任务回执。 */
public record GenerationTaskIdempotencyRecord(
        GenerationTaskSubmissionReceipt submission,
        String requestFingerprint
) {

    /** 创建幂等记录并校验请求指纹。 */
    public GenerationTaskIdempotencyRecord {
        Objects.requireNonNull(submission, "生成任务幂等回执不能为空");
        if (requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("生成任务请求指纹格式无效");
        }
    }
}
