package com.rush.rushaicodemother.orchestration.tool;

import java.util.Map;
import java.util.TreeMap;

/** 在执行任何审批门控副作用之前，工具发出控制流信号。 */
public final class GenerationApprovalRequiredException extends RuntimeException {

    private final String taskId;
    private final DestructiveToolAction action;
    private final String approvalId;
    private final Map<String, Object> requestDetails;
    private volatile boolean suspensionPrepared;

    /**
 * 创建生成审批{@code Required}异常实例并完成必要的依赖和初始状态设置。
 *
 * @param taskId 任务编号
 * @param action 动作
 * @param approvalId 审批编号
 * @param requestDetails 请求详情
 */
    public GenerationApprovalRequiredException(String taskId,
                                               DestructiveToolAction action,
                                               String approvalId,
                                               Map<String, Object> requestDetails) {
        super("破坏性工具操作需要人工审批", null, false, false);
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

    boolean suspensionPrepared() {
        return suspensionPrepared;
    }

    void markSuspensionPrepared() {
        suspensionPrepared = true;
    }
}
