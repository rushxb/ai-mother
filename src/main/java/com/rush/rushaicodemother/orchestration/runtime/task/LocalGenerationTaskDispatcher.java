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

    @Override
    public void dispatch(String taskId) {
        GenerationTaskDispatchResult result = executionService.schedule(taskId, null);
        if (result == GenerationTaskDispatchResult.RETRY) {
            throw new GenerationTaskCapacityExceededException(
                    "Generation task could not acquire a local worker reservation: " + taskId);
        }
    }
}
