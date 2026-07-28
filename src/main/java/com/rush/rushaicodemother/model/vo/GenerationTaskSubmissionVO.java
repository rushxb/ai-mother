package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSubmissionReceipt;

import java.time.Instant;

/** 接受生成任务后返回确认。 */
public record GenerationTaskSubmissionVO(
        String taskId,
        Long appId,
        String route,
        String status,
        Instant submittedAt,
        Instant deadlineAt
) {
    /**
 * 根据输入数据创建当前对象。
 *
 * @param submission 提交回执
 * @return 生成任务提交视图对象
 */
    public static GenerationTaskSubmissionVO from(GenerationTaskSubmissionReceipt submission) {
        return new GenerationTaskSubmissionVO(
                submission.taskId(), submission.appId(), submission.route(), submission.status().getValue(),
                submission.submittedAt(), submission.deadlineAt());
    }
}
