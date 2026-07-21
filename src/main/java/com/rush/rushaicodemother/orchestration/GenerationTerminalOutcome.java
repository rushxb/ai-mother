package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;

import java.util.Locale;

/**
 * Canonical terminal outcomes shared by stream completion, telemetry and task events.
 */
public enum GenerationTerminalOutcome {

    SUCCESS(GenerationTaskStatus.SUCCESS, GenerationEventType.TASK_DONE, "生成任务完成"),
    FAILED(GenerationTaskStatus.FAILED, GenerationEventType.TASK_FAILED, "生成任务失败"),
    CANCELLED(GenerationTaskStatus.CANCELLED, GenerationEventType.TASK_CANCELLED, "生成任务已取消"),
    DEADLINE_EXCEEDED(GenerationTaskStatus.DEADLINE_EXCEEDED, GenerationEventType.TASK_TIMED_OUT, "生成任务已超时");

    private final GenerationTaskStatus taskStatus;
    private final GenerationEventType eventType;
    private final String eventMessage;

    GenerationTerminalOutcome(GenerationTaskStatus taskStatus,
                              GenerationEventType eventType,
                              String eventMessage) {
        this.taskStatus = taskStatus;
        this.eventType = eventType;
        this.eventMessage = eventMessage;
    }

    public String status() {
        return taskStatus.getValue();
    }

    public GenerationTaskStatus taskStatus() {
        return taskStatus;
    }

    public GenerationEventType eventType() {
        return eventType;
    }

    public String eventMessage() {
        return eventMessage;
    }

    public static GenerationTerminalOutcome resolve(GenerationSession session, Throwable throwable) {
        if (session != null && session.executionContext() != null) {
            var context = session.executionContext();
            if (context.isCancelled()) {
                return isDeadlineReason(context.cancellationReason()) ? DEADLINE_EXCEEDED : CANCELLED;
            }
            if (context.isDeadlineExceeded()) {
                return DEADLINE_EXCEEDED;
            }
        }
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof GenerationDeadlineExceededException) {
                return DEADLINE_EXCEEDED;
            }
        }
        if (session != null && session.isCancelled()) {
            return CANCELLED;
        }
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof GenerationStoppedException
                    || current instanceof GenerationExecutionCancelledException) {
                return CANCELLED;
            }
        }
        return FAILED;
    }

    private static boolean isDeadlineReason(String reason) {
        return reason != null && reason.toLowerCase(Locale.ROOT).contains("deadline");
    }
}
