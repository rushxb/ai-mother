package com.rush.rushaicodemother.orchestration.event;

public enum GenerationEventType {

    TASK_ROUTE("task_route"),
    GENERATION_START("generation_start"),
    EDIT_ROUTE("edit_route"),
    FILE_LOCATOR("file_locator"),
    PATCH_APPLY("patch_apply"),
    VALIDATION_START("validation_start"),
    VALIDATION_RESULT("validation_result"),
    INDEX_UPDATE("index_update"),
    TASK_DONE("task_done"),
    TASK_FAILED("task_failed");

    private final String value;

    GenerationEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
