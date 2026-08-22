package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.config.GenerationTaskAdmissionProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 集群范围的并发准入策略，限制用户总任务并避免同一应用重复生成。 */
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
        assertApplicationAvailable(context.snapshot().appNonTerminalTasks());
    }

    @Override
    public void assertMayPreflight(GenerationTaskPreflightAdmissionContext context) {
        assertUserCapacity(context.snapshot().userNonTerminalTasks());
        assertApplicationAvailable(context.snapshot().appNonTerminalTasks());
    }

    /**
     * 低置信度澄清前快速拒绝应用忙状态，避免产生无效 provider 成本。
     * 最终持久化入口仍保留应用行锁与计数校验，负责覆盖预检之后的并发竞态。
     */
    private void assertApplicationAvailable(int currentNonTerminalTasks) {
        if (currentNonTerminalTasks < 0) {
            throw new IllegalArgumentException("current application task count cannot be negative");
        }
        if (currentNonTerminalTasks > 0) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "当前应用已有进行中的生成任务，请等待完成或先取消后再试"
            );
        }
    }
}
