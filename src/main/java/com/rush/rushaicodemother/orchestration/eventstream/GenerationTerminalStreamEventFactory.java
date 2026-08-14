package com.rush.rushaicodemother.orchestration.eventstream;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

import java.util.Map;

/** 生成任务终态在实时流、Redis 重放与数据库回退之间共享的稳定公开投影。 */
public final class GenerationTerminalStreamEventFactory {

    private GenerationTerminalStreamEventFactory() {
    }

    public static GenerationStreamEvent create(String taskId, GenerationTaskStatus status) {
        if (taskId == null || taskId.isBlank() || status == null || !status.isTerminal()) {
            throw new IllegalArgumentException("terminal task identity is required");
        }
        String statusValue = status.getValue();
        String outcome = switch (status) {
            case SUCCESS -> "done";
            case CANCELLED -> "cancelled";
            case DEADLINE_EXCEEDED -> "timed_out";
            default -> "failed";
        };
        return GenerationStreamEvent.taskTerminal(message(status), Map.of(
                "taskId", taskId,
                "status", statusValue,
                "outcome", outcome,
                "terminal", true,
                "eventId", taskId + ":" + statusValue + ":durable-terminal"
        ));
    }

    private static String message(GenerationTaskStatus status) {
        return switch (status) {
            case SUCCESS -> "项目生成完成";
            case CANCELLED -> "项目生成已取消";
            case DEADLINE_EXCEEDED -> "项目生成超时";
            case FAILED -> "项目生成失败";
            default -> "项目生成已结束";
        };
    }
}
