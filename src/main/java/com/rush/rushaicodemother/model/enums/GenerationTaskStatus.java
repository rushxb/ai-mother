package com.rush.rushaicodemother.model.enums;

import lombok.Getter;

import java.util.Arrays;

/** 生成任务持久化状态。 */
@Getter
public enum GenerationTaskStatus {

    QUEUED("queued", false),
    RUNNING("running", false),
    WAITING_APPROVAL("waiting_approval", false),
    SUCCESS("success", true),
    FAILED("failed", true),
    CANCELLED("cancelled", true),
    DEADLINE_EXCEEDED("deadline_exceeded", true);

    private final String value;
    private final boolean terminal;

    GenerationTaskStatus(String value, boolean terminal) {
        this.value = value;
        this.terminal = terminal;
    }

    public static GenerationTaskStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElse(null);
    }
}
