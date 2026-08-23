package com.rush.rushaicodemother.orchestration.runtime.task;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 在提交过程中执行持久命令的开发适配器。 */
@Component
@ConditionalOnProperty(prefix = "app.generation-task-queue", name = "transport",
        havingValue = "local", matchIfMissing = true)
public class LocalGenerationTaskDispatcher implements GenerationTaskDispatcher {

    private final GenerationTaskCommandExecutionService executionService;

    public LocalGenerationTaskDispatcher(GenerationTaskCommandExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
 * 分发{@code Local}生成任务{@code Dispatcher}。
 *
 * @param taskId 任务编号
 */
    @Override
    public GenerationTaskDispatchResult dispatch(String taskId) {
        try {
            return executionService.schedule(taskId, null);
        } catch (GenerationTaskCapacityExceededException capacityExceeded) {
            // 执行器已释放 claim；保留 QUEUED，由持久重分发扫描在容量恢复后重试。
            return GenerationTaskDispatchResult.RETRY;
        }
    }
}
