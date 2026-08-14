package com.rush.rushaicodemother.orchestration.finalization;

/** 终态提交后可重试的事件与资源清理工作项。 */
public record GenerationTerminalEffect(
        String taskId,
        Long appId,
        Long userId,
        String route,
        GenerationFinalizationCommand command,
        int attempts,
        long completedOperationMask
) {

    public GenerationTerminalEffect(String taskId,
                                    Long appId,
                                    Long userId,
                                    String route,
                                    GenerationFinalizationCommand command,
                                    int attempts) {
        this(taskId, appId, userId, route, command, attempts, 0L);
    }

    public GenerationTerminalEffect {
        if (completedOperationMask < 0) {
            throw new IllegalArgumentException("终态副作用回执标记不能为负数");
        }
    }

    public String eventId() {
        return "terminal:" + taskId + ":" + command.executionFence().executionEpoch();
    }

    public boolean pending(GenerationTerminalEffectOperation operation) {
        if (operation == null) {
            throw new IllegalArgumentException("终态副作用操作不能为空");
        }
        return (completedOperationMask & operation.mask()) == 0L;
    }
}
