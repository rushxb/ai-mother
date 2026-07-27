package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.config.GenerationTaskAdmissionProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 集群范围的用户准入策略，防止一个帐户垄断持久工作。 */
@Component
@RequiredArgsConstructor
public class GenerationTaskConcurrencyAdmissionPolicy {

    private final GenerationTaskAdmissionProperties properties;

    public void assertMayCreate(int current) {
        if (current < 0) {
            throw new IllegalArgumentException("current non-terminal task count cannot be negative");
        }
        int limit = properties.getMaxNonTerminalTasksPerUser();
        if (current >= limit) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "当前用户同时进行中的生成任务已达到上限（" + limit + "）"
            );
        }
    }
}
