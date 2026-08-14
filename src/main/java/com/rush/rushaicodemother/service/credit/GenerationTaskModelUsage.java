package com.rush.rushaicodemother.service.credit;

/**
 * 生成任务的模型用量完整性快照。
 *
 * <p>该值将“一次模型都未成功”与“成功调用的 usage 不可用”分开，避免结算将两者都
 * 压缩成 {@code 0}。</p>
 */
public record GenerationTaskModelUsage(
        long totalTokens,
        long successfulCallCount,
        long pendingCallCount
) {

    public GenerationTaskModelUsage {
        if (totalTokens < 0 || successfulCallCount < 0 || pendingCallCount < 0
                || (successfulCallCount == 0 && totalTokens != 0)) {
            throw new IllegalArgumentException("generation task model usage is inconsistent");
        }
    }

    public static GenerationTaskModelUsage none() {
        return new GenerationTaskModelUsage(0L, 0L, 0L);
    }

    public boolean hasPendingCalls() {
        return pendingCallCount > 0;
    }
}
