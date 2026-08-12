package com.rush.rushaicodemother.orchestration.finalization;

/** 终态提交后可重试的事件与资源清理工作项。 */
public record GenerationTerminalEffect(
        String taskId,
        Long appId,
        Long userId,
        String route,
        GenerationFinalizationCommand command,
        int attempts
) {

    public String eventId() {
        return "terminal:" + taskId + ":" + command.executionFence().executionEpoch();
    }
}
