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
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/** Durable, target-bound and one-time approvals for destructive AI tool actions. */
@Service
public class ToolApprovalService {

    private final ToolApprovalRepository approvalRepository;
    private final DurableGenerationTaskRepository taskRepository;
    private final AiToolApprovalProperties properties;
    private final GenerationSessionRegistry sessionRegistry;
    private final Clock clock;

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

    public ToolApprovalRecord approve(String taskId,
                                      DestructiveToolAction action,
                                      String approvalId,
                                      Long approvedBy) {
        validateDecision(taskId, action, approvalId, approvedBy);
        Instant now = clock.instant();
        requireDecidableTask(taskId, approvedBy, now);
        if (approvalRepository.approve(taskId, action, approvalId, approvedBy, now)) {
            return requireDecision(taskId, action, approvalId, ToolApprovalStatus.APPROVED, approvedBy);
        }
        ToolApprovalRecord current = approvalRepository.find(taskId, action, approvalId).orElse(null);
        if (current != null
                && current.status() == ToolApprovalStatus.APPROVED
                && approvedBy.equals(current.decidedBy())) {
            return current;
        }
        throw unavailableApproval();
    }

    public ToolApprovalRecord beginExecution(ToolApprovalRecord approval) {
        if (approval == null || approval.invocationCheckpoint() == null) {
            throw new IllegalArgumentException("approved tool invocation is incomplete");
        }
        requireIdentity(approval.taskId(), approval.action(), approval.approvalId());
        Instant now = clock.instant();
        requireRunningTask(approval.taskId(), now);
        ToolInvocationCheckpoint checkpoint = approval.invocationCheckpoint();
        boolean started = approvalRepository.beginExecution(
                approval.taskId(), approval.action(), approval.approvalId(),
                checkpoint.requestId(), now, properties.getMaxExecutionAttempts());
        ToolApprovalRecord current = approvalRepository.find(
                        approval.taskId(), approval.action(), approval.approvalId())
                .orElseThrow(this::unavailableApproval);
        if ((current.status() == ToolApprovalStatus.EXECUTING && !started)
                || (current.status() != ToolApprovalStatus.EXECUTING
                    && current.status() != ToolApprovalStatus.CONSUMED)) {
            throw unavailableApproval();
        }
        return current;
    }

    public ToolApprovalRecord completeExecution(ToolApprovalRecord executing,
                                                ToolExecutionOutcome outcome) {
        if (executing == null || executing.invocationCheckpoint() == null || outcome == null) {
            throw new IllegalArgumentException("tool execution completion is incomplete");
        }
        Instant now = clock.instant();
        boolean completed = approvalRepository.completeExecution(
                executing.taskId(), executing.action(), executing.approvalId(),
                executing.invocationCheckpoint().requestId(), outcome, now);
        ToolApprovalRecord current = approvalRepository.find(
                        executing.taskId(), executing.action(), executing.approvalId())
                .orElseThrow(this::unavailableApproval);
        if ((!completed && current.status() != ToolApprovalStatus.CONSUMED)
                || current.executionOutcome() == null) {
            throw unavailableApproval();
        }
        return current;
    }

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
        ToolApprovalRecord approval = approvalRepository.find(taskId, action, approvalId).orElse(null);
        ToolInvocationCheckpoint checkpoint = approval == null ? null : approval.invocationCheckpoint();
        return approval != null
                && approval.status() == ToolApprovalStatus.EXECUTING
                && checkpoint != null
                && checkpoint.requestId().equals(invocation.requestId())
                && checkpoint.toolName().equals(invocation.toolName())
                && checkpoint.argumentsDigest().equals(invocation.argumentsDigest());
    }

    public ToolApprovalRecord reject(String taskId,
                                     DestructiveToolAction action,
                                     String approvalId,
                                     Long rejectedBy) {
        validateDecision(taskId, action, approvalId, rejectedBy);
        Instant now = clock.instant();
        requireDecidableTask(taskId, rejectedBy, now);
        if (!approvalRepository.reject(taskId, action, approvalId, rejectedBy, now)) {
            ToolApprovalRecord current = approvalRepository.find(taskId, action, approvalId).orElse(null);
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
        return requireDecision(taskId, action, approvalId, ToolApprovalStatus.REJECTED, rejectedBy);
    }

    public void requestApproval(String taskId,
                                DestructiveToolAction action,
                                String approvalId,
                                Map<String, Object> requestDetails) {
        requestApproval(taskId, action, approvalId, requestDetails, null);
    }

    public ToolApprovalRecord requestApproval(String taskId,
                                              DestructiveToolAction action,
                                              String approvalId,
                                              Map<String, Object> requestDetails,
                                              ToolInvocationCheckpoint checkpoint) {
        requireIdentity(taskId, action, approvalId);
        DurableGenerationTaskRecord task = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND_ERROR, "生成任务不存在，无法创建工具审批"));
        Instant requestedAt = clock.instant();
        requireRunningTask(task, requestedAt);
        Map<String, Object> normalizedDetails = requestDetails == null
                ? Map.of()
                : new TreeMap<>(requestDetails);
        ToolApprovalRecord persisted = approvalRepository.createPending(new ToolApprovalRecord(
                approvalId,
                taskId,
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

    public void expireApprovals() {
        approvalRepository.expireBefore(clock.instant(), properties.getExpirationBatchSize());
    }

    public ToolApprovalRecord attachInvocationCheckpoint(String taskId,
                                                         DestructiveToolAction action,
                                                         String approvalId,
                                                         ToolInvocationCheckpoint checkpoint) {
        requireIdentity(taskId, action, approvalId);
        requireRunningTask(taskId, clock.instant());
        return approvalRepository.attachInvocationCheckpoint(
                taskId, action, approvalId, checkpoint);
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

    private void requireDecidableTask(String taskId, Long actorId, Instant now) {
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
                                               DestructiveToolAction action,
                                               String approvalId,
                                               ToolApprovalStatus status,
                                               Long decidedBy) {
        ToolApprovalRecord decision = approvalRepository.find(taskId, action, approvalId)
                .orElseThrow(this::unavailableApproval);
        if (decision.status() != status || !decidedBy.equals(decision.decidedBy())) {
            throw unavailableApproval();
        }
        return decision;
    }
}
