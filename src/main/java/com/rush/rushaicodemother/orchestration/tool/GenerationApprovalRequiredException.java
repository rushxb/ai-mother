package com.rush.rushaicodemother.orchestration.tool;

import java.util.Map;
import java.util.TreeMap;

/** 在执行任何审批门控副作用之前，工具发出控制流信号。 */
public final class GenerationApprovalRequiredException extends RuntimeException {

    private final String taskId;
    private final DestructiveToolAction action;
    private final String approvalId;
    private final Map<String, Object> requestDetails;

    public GenerationApprovalRequiredException(String taskId,
                                               DestructiveToolAction action,
                                               String approvalId,
                                               Map<String, Object> requestDetails) {
        super("destructive tool action requires approval", null, false, false);
        this.taskId = taskId;
        this.action = action;
        this.approvalId = approvalId;
        this.requestDetails = requestDetails == null
                ? Map.of()
                : Map.copyOf(new TreeMap<>(requestDetails));
    }

    public String taskId() {
        return taskId;
    }

    public DestructiveToolAction action() {
        return action;
    }

    public String approvalId() {
        return approvalId;
    }

    public Map<String, Object> requestDetails() {
        return requestDetails;
    }
}
