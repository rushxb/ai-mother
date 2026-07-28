package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;

import java.time.Instant;
import java.util.Objects;

/** 任务准入事务提交后返回的不可变确认信息。 */
public record GenerationTaskSubmissionReceipt(
        String taskId,
        Long appId,
        String route,
        GenerationTaskStatus status,
        Instant submittedAt,
        Instant deadlineAt
) {

    /** 创建提交回执并校验其持久化身份与时间边界。 */
    public GenerationTaskSubmissionReceipt {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("生成任务 ID 格式无效");
        }
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("生成任务应用 ID 必须为正数");
        }
        if (route == null || route.isBlank()) {
            throw new IllegalArgumentException("生成任务路由不能为空");
        }
        Objects.requireNonNull(status, "生成任务状态不能为空");
        Objects.requireNonNull(submittedAt, "生成任务提交时间不能为空");
        Objects.requireNonNull(deadlineAt, "生成任务截止时间不能为空");
        if (!deadlineAt.isAfter(submittedAt)) {
            throw new IllegalArgumentException("生成任务截止时间必须晚于提交时间");
        }
    }

    /** 根据刚完成持久化的任务命令创建排队回执。 */
    public static GenerationTaskSubmissionReceipt queued(GenerationTaskCommand command) {
        Objects.requireNonNull(command, "生成任务命令不能为空");
        return new GenerationTaskSubmissionReceipt(
                command.taskId(),
                command.appId(),
                command.route(),
                GenerationTaskStatus.QUEUED,
                command.submittedAt(),
                command.deadlineAt()
        );
    }
}
