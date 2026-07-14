package com.rush.rushaicodemother.orchestration.event;

public enum GenerationEventType {

    TASK_ROUTE("task_route"),
    GENERATION_START("generation_start"),
    EDIT_ROUTE("edit_route"),
    AGENT_EDIT_READ("agent_edit_read"),
    AGENT_EDIT_UNDERSTAND("agent_edit_understand"),
    AGENT_EDIT_PLAN("agent_edit_plan"),
    AGENT_EDIT_VERIFY("agent_edit_verify"),
    FILE_LOCATOR("file_locator"),
    PATCH_APPLY("patch_apply"),
    REPAIR_START("repair_start"),
    EDIT_ROLLBACK("edit_rollback"),
    VALIDATION_START("validation_start"),
    VALIDATION_RESULT("validation_result"),
    DEV_SERVER_VALIDATION_RESULT("dev_server_validation_result"),
    INDEX_UPDATE("index_update"),
    TASK_DONE("task_done"),
    TASK_FAILED("task_failed"),
    TASK_CANCELLED("task_cancelled"),
    TASK_TIMED_OUT("task_timed_out");

    private final String value;

    GenerationEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
