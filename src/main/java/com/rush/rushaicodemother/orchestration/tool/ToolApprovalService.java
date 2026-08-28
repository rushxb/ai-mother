package com.rush.rushaicodemother.orchestration.tool;

import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.config.AiToolApprovalProperties;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/** 对破坏性 AI 工具操作进行持久、有目标限制的一次性批准。 */
@Service
public class ToolApprovalService {

    private final ToolApprovalRepository approvalRepository;
    private final DurableGenerationTaskRepository taskRepository;
    private final AiToolApprovalProperties properties;
    private final GenerationSessionRegistry sessionRegistry;
    private final Clock clock;

    @Autowired
    public ToolApprovalService(ToolApprovalRepository approvalRepository,
                               DurableGenerationTaskRepository taskRepository,
                               AiToolApprovalProperties properties,
                               GenerationSessionRegistry sessionRegistry) {
        this(approvalRepository, taskRepository, properties, sessionRegistry, Clock.systemUTC());
    }

    ToolApprovalService(ToolApprovalRepository approvalRepository,
                        DurableGenerationTaskRepository taskRepository,
                        AiToolApprovalProperties properties,
                        GenerationSessionRegistry sessionRegistry,
                        Clock clock) {
        this.approvalRepository = approvalRepository;
        this.taskRepository = taskRepository;
        this.properties = properties;
        this.sessionRegistry = sessionRegistry;
        this.clock = clock;
    }

    /**
 * 审批并返回。
 *
 * @param taskId 任务编号
 * @param action 动作
 * @param approvalId 审批编号
 * @param approvedBy 已审批按
 * @return 工具审批
 */
    public ToolApprovalRecord approve(String taskId,
                                      DestructiveToolAction action,
                                      String approvalId,
                                      Long approvedBy) {
        validateDecision(taskId, action, approvalId, approvedBy);
        Instant now = clock.instant();
        DurableGenerationTaskRecord task = requireDecidableTask(taskId, approvedBy, now);
        long requestExecutionEpoch = requireExecutionEpoch(task);
        if (approvalRepository.approve(
                taskId, requestExecutionEpoch, action, approvalId, approvedBy, now)) {
            return requireDecision(taskId, requestExecutionEpoch, action, approvalId,
                    ToolApprovalStatus.APPROVED, approvedBy);
        }
        ToolApprovalRecord current = approvalRepository.find(
                taskId, requestExecutionEpoch, action, approvalId).orElse(null);
        if (current != null
                && current.status() == ToolApprovalStatus.APPROVED
                && approvedBy.equals(current.decidedBy())) {
            return current;
        }
        throw unavailableApproval();
    }

    /**
 * 开始执行。
 *
 * @param approval 审批
 * @return 执行
 */
    public ToolApprovalRecord beginExecution(ToolApprovalRecord approval) {
        if (approval == null || approval.invocationCheckpoint() == null) {
            throw new IllegalArgumentException("approved tool invocation is incomplete");
        }
        requireIdentity(approval.taskId(), approval.action(), approval.approvalId());
        requireRequestExecutionEpoch(approval.requestExecutionEpoch());
        Instant now = clock.instant();
        requireRunningTask(approval.taskId(), now);
        ToolInvocationCheckpoint checkpoint = approval.invocationCheckpoint();
        boolean started = approvalRepository.beginExecution(
                approval.taskId(), approval.requestExecutionEpoch(),
                approval.action(), approval.approvalId(),
                checkpoint.requestId(), now, properties.getMaxExecutionAttempts());
        ToolApprovalRecord current = approvalRepository.find(
                        approval.taskId(), approval.requestExecutionEpoch(),
                        approval.action(), approval.approvalId())
                .orElseThrow(this::unavailableApproval);
        if ((current.status() == ToolApprovalStatus.EXECUTING && !started)
                || (current.status() != ToolApprovalStatus.EXECUTING
                    && current.status() != ToolApprovalStatus.CONSUMED)) {
            throw unavailableApproval();
        }
        return current;
    }

    /**
 * 完成执行并持久化终态。
 *
 * @param executing {@code executing} 对应的调用参数
 * @param outcome 结果
 * @return 执行
 */
    public ToolApprovalRecord completeExecution(ToolApprovalRecord executing,
                                                ToolExecutionOutcome outcome) {
        if (executing == null || executing.invocationCheckpoint() == null || outcome == null) {
            throw new IllegalArgumentException("tool execution completion is incomplete");
        }
        requireRequestExecutionEpoch(executing.requestExecutionEpoch());
        Instant now = clock.instant();
        boolean completed = approvalRepository.completeExecution(
                executing.taskId(), executing.requestExecutionEpoch(),
                executing.action(), executing.approvalId(),
                executing.invocationCheckpoint().requestId(), outcome, now);
        ToolApprovalRecord current = approvalRepository.find(
                        executing.taskId(), executing.requestExecutionEpoch(),
                        executing.action(), executing.approvalId())
                .orElseThrow(this::unavailableApproval);
        if ((!completed && current.status() != ToolApprovalStatus.CONSUMED)
                || current.executionOutcome() == null) {
            throw unavailableApproval();
        }
        return current;
    }

    /**
 * 判断执行{@code Authorized}是否满足约束。
 *
 * @param taskId 任务编号
 * @param action 动作
 * @param approvalId 审批编号
 * @param invocation 调用
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean isExecutionAuthorized(
            String taskId,
            DestructiveToolAction action,
            String approvalId,
            GenerationToolExecutionContextService.ToolInvocationExecution invocation
    ) {
        requireIdentity(taskId, action, approvalId);
        if (invocation == null || !taskId.equals(invocation.taskId())) {
            return false;
        }
        ToolApprovalRecord approval = approvalRepository.find(
                taskId, invocation.requestExecutionEpoch(), action, approvalId).orElse(null);
        ToolInvocationCheckpoint checkpoint = approval == null ? null : approval.invocationCheckpoint();
        return approval != null
                && approval.requestExecutionEpoch() == invocation.requestExecutionEpoch()
                && approval.status() == ToolApprovalStatus.EXECUTING
                && checkpoint != null
                && checkpoint.requestId().equals(invocation.requestId())
                && checkpoint.toolName().equals(invocation.toolName())
                && checkpoint.argumentsDigest().equals(invocation.argumentsDigest());
    }

    /**
 * 拒绝工具审批并记录原因。
 *
 * @param taskId 任务编号
 * @param action 动作
 * @param approvalId 审批编号
 * @param rejectedBy {@code rejectedBy} 对应的调用参数
 * @return 工具审批
 */
    public ToolApprovalRecord reject(String taskId,
                                     DestructiveToolAction action,
                                     String approvalId,
                                     Long rejectedBy) {
        validateDecision(taskId, action, approvalId, rejectedBy);
        Instant now = clock.instant();
        DurableGenerationTaskRecord task = requireDecidableTask(taskId, rejectedBy, now);
        long requestExecutionEpoch = requireExecutionEpoch(task);
        if (!approvalRepository.reject(
                taskId, requestExecutionEpoch, action, approvalId, rejectedBy, now)) {
            ToolApprovalRecord current = approvalRepository.find(
                    taskId, requestExecutionEpoch, action, approvalId).orElse(null);
            if (current == null
                    || current.status() != ToolApprovalStatus.REJECTED
                    || !rejectedBy.equals(current.decidedBy())) {
                throw unavailableApproval();
            }
        }
        GenerationSession session = sessionRegistry.getByTaskId(taskId);
        if (session != null) {
            session.emit(GenerationStreamEvent.agentEvent("", Map.of(
                    "agent", "PermissionPolicy",
                    "stage", "approval",
                    "status", "approval_rejected",
                    "summary", "The project owner rejected the destructive tool action",
                    "taskId", taskId,
                    "action", action.value(),
                    "approvalId", approvalId,
                    "rejectedBy", rejectedBy
            )));
        }
        return requireDecision(taskId, requestExecutionEpoch, action, approvalId,
                ToolApprovalStatus.REJECTED, rejectedBy);
    }

    /**
 * 返回请求审批。
 *
 * @param taskId 任务编号
 * @param action 动作
 * @param approvalId 审批编号
 * @param requestDetails 请求详情
 * @param checkpoint 检查点
 * @return 工具审批
 */
    public ToolApprovalRecord requestApproval(String taskId,
                                              DestructiveToolAction action,
                                              String approvalId,
                                              Map<String, Object> requestDetails,
                                              ToolInvocationCheckpoint checkpoint) {
        requireIdentity(taskId, action, approvalId);
        if (checkpoint == null) {
            throw new IllegalArgumentException("tool invocation checkpoint is required");
        }
        DurableGenerationTaskRecord task = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND_ERROR, "生成任务不存在，无法创建工具审批"));
        Instant requestedAt = clock.instant();
        requireRunningTask(task, requestedAt);
        Map<String, Object> normalizedDetails = requestDetails == null
                ? Map.of()
                : new TreeMap<>(requestDetails);
        long requestExecutionEpoch = requireExecutionEpoch(task);
        ToolApprovalRecord persisted = approvalRepository.createPending(new ToolApprovalRecord(
                approvalId,
                taskId,
                requestExecutionEpoch,
                task.appId(),
                task.userId(),
                action,
                JSONUtil.toJsonStr(normalizedDetails),
                ToolApprovalStatus.PENDING,
                requestedAt,
                requestedAt.plus(properties.getTtl()),
                null,
                null,
                null,
                0,
                checkpoint
        ));
        if (persisted.status() != ToolApprovalStatus.PENDING) {
            throw unavailableApproval();
        }
        GenerationSession session = sessionRegistry.getByTaskId(taskId);
        if (session != null) {
            session.emit(GenerationStreamEvent.agentEvent("", Map.of(
                    "agent", "PermissionPolicy",
                    "stage", "approval",
                    "status", "approval_required",
                    "summary", "A destructive tool action requires project-owner approval",
                    "taskId", taskId,
                    "action", action.value(),
                    "approvalId", approvalId,
                    "request", normalizedDetails,
                    "oneTime", true,
                    "expiresAt", persisted.expiresAt().toString()
            )));
        }
        return persisted;
    }

    /** 处理{@code expire}{@code Approvals}。 */
    public void expireApprovals() {
        approvalRepository.expireBefore(clock.instant(), properties.getExpirationBatchSize());
    }

    /**
 * 为当前上下文附加调用检查点。
 *
 * @param taskId 任务编号
 * @param action 动作
 * @param approvalId 审批编号
 * @param checkpoint 检查点
 * @return 调用检查点
 */
    public ToolApprovalRecord attachInvocationCheckpoint(String taskId,
                                                         DestructiveToolAction action,
                                                         String approvalId,
                                                         ToolInvocationCheckpoint checkpoint) {
        requireIdentity(taskId, action, approvalId);
        DurableGenerationTaskRecord task = requireRunningTask(taskId, clock.instant());
        long requestExecutionEpoch = requireExecutionEpoch(task);
        return approvalRepository.attachInvocationCheckpoint(
                taskId, requestExecutionEpoch, action, approvalId, checkpoint);
    }

    private void validateDecision(String taskId,
                                  DestructiveToolAction action,
                                  String approvalId,
                                  Long actorId) {
        requireIdentity(taskId, action, approvalId);
        if (actorId == null || actorId <= 0) {
            throw new IllegalArgumentException("tool approval actor is invalid");
        }
    }

    /** 校验并返回有效的{@code Identity}。 */
    private void requireIdentity(String taskId,
                                 DestructiveToolAction action,
                                 String approvalId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("invalid generation task id");
        }
        if (action == null) {
            throw new IllegalArgumentException("tool approval action is required");
        }
        if (approvalId == null || !approvalId.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid tool approval id");
        }
    }

    private DurableGenerationTaskRecord requireRunningTask(String taskId, Instant now) {
        DurableGenerationTaskRecord task = taskRepository.findByTaskId(taskId)
                .orElseThrow(this::unavailableApproval);
        requireRunningTask(task, now);
        return task;
    }

    private void requireRunningTask(DurableGenerationTaskRecord task, Instant now) {
        if (task.status() != GenerationTaskStatus.RUNNING || !canAct(task, now)) {
            throw unavailableApproval();
        }
    }

    /** 校验并返回有效的{@code Decidable}任务。 */
    private DurableGenerationTaskRecord requireDecidableTask(String taskId, Long actorId, Instant now) {
        DurableGenerationTaskRecord task = taskRepository.findByTaskId(taskId)
                .orElseThrow(this::unavailableApproval);
        if (!actorId.equals(task.userId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权决策该生成任务的工具审批");
        }
        if ((task.status() != GenerationTaskStatus.RUNNING
                && task.status() != GenerationTaskStatus.WAITING_APPROVAL)
                || !canAct(task, now)) {
            throw unavailableApproval();
        }
        return task;
    }

    private boolean canAct(DurableGenerationTaskRecord task, Instant now) {
        return task != null
                && !task.cancellationRequested()
                && (task.deadlineAt() == null || task.deadlineAt().isAfter(now));
    }

    private BusinessException unavailableApproval() {
        return new BusinessException(ErrorCode.OPERATION_ERROR, "审批请求不存在、已过期或状态已变更");
    }

    private ToolApprovalRecord requireDecision(String taskId,
                                               long requestExecutionEpoch,
                                               DestructiveToolAction action,
                                               String approvalId,
                                               ToolApprovalStatus status,
                                               Long decidedBy) {
        ToolApprovalRecord decision = approvalRepository.find(
                        taskId, requestExecutionEpoch, action, approvalId)
                .orElseThrow(this::unavailableApproval);
        if (decision.status() != status || !decidedBy.equals(decision.decidedBy())) {
            throw unavailableApproval();
        }
        return decision;
    }

    private long requireExecutionEpoch(DurableGenerationTaskRecord task) {
        long executionEpoch = task == null ? 0L : task.executionEpoch();
        requireRequestExecutionEpoch(executionEpoch);
        return executionEpoch;
    }

    private void requireRequestExecutionEpoch(long requestExecutionEpoch) {
        if (requestExecutionEpoch <= 0) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR, "工具审批请求缺少可证明的执行纪元");
        }
    }
}
