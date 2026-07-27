package com.rush.rushaicodemother.orchestration.feedback;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

/**
 * 项目生成任务收到明确的用户反馈后发出的域信号。
 *
 * <p>服务层发布此端口级对象以便下游AI改进通道
 * 可以独立发展：语义记忆、基准挖掘、分析或产品指导
 * 无需将反馈持久性耦合到特定基础设施适配器即可订阅。</p>
 */
public record GenerationFeedbackSignal(
        String taskId,
        Long appId,
        Long tenantId,
        Long userId,
        GenerationTaskStatus taskStatus,
        int rating,
        String outcome,
        String comment
) {

    public boolean improvementCandidate() {
        return rating <= 2;
    }
}
