package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.config.GenerationTaskAdmissionProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 集群范围的用户并发准入策略；应用并发由独立应用控制策略负责。 */
@Component
@RequiredArgsConstructor
public class GenerationTaskConcurrencyAdmissionPolicy implements GenerationTaskAdmissionPolicy {

    private final GenerationTaskAdmissionProperties properties;

    /** 校验当前用户是否仍有创建新任务的并发容量。 */
    void assertUserCapacity(int currentNonTerminalTasks) {
        if (currentNonTerminalTasks < 0) {
            throw new IllegalArgumentException("current non-terminal task count cannot be negative");
        }
        int limit = properties.getMaxNonTerminalTasksPerUser();
        if (currentNonTerminalTasks >= limit) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "当前用户同时进行中的生成任务已达到上限（" + limit + "）"
            );
        }
    }

    @Override
    public void assertMayAdmit(GenerationTaskAdmissionContext context) {
        assertUserCapacity(context.snapshot().userNonTerminalTasks());
    }

    @Override
    public void assertMayPreflight(GenerationTaskPreflightAdmissionContext context) {
        assertUserCapacity(context.snapshot().userNonTerminalTasks());
    }
}
