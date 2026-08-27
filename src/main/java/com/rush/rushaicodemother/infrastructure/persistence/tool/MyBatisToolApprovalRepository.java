package com.rush.rushaicodemother.infrastructure.persistence.tool;

import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.GenerationToolApprovalMapper;
import com.rush.rushaicodemother.model.entity.GenerationToolApproval;
import com.rush.rushaicodemother.orchestration.tool.DestructiveToolAction;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalRecord;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalRepository;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalStatus;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionOutcome;
import com.rush.rushaicodemother.orchestration.tool.ToolInvocationCheckpoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.List;

/**
 * MyBatis工具审批持久化仓储。
 */
@Repository
@RequiredArgsConstructor
public class MyBatisToolApprovalRepository implements ToolApprovalRepository {

    private static final int MAX_EXPIRE_BATCH = 1000;
    private static final int MAX_TOOL_ARGUMENTS_CHARS = 65_536;
    private static final int MAX_RUNTIME_CHECKPOINT_CHARS = 524_288;
    private static final int MAX_EXECUTION_RESULT_CHARS = 65_536;
    private final GenerationToolApprovalMapper mapper;
    private final ZoneId databaseZone = ZoneId.systemDefault();

    /**
 * 创建{@code Pending}。
 *
 * @param approval 审批
 * @return {@code Pending}
 */
    @Override
    public ToolApprovalRecord createPending(ToolApprovalRecord approval) {
        validateRecord(approval);
        mapper.insertPending(toEntity(approval));
        ToolApprovalRecord persisted = find(approval.taskId(), approval.action(), approval.approvalId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.OPERATION_ERROR, "工具审批请求持久化失败"));
        if (!sameRequest(persisted, approval)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "工具审批标识与已有请求冲突");
        }
        return persisted;
    }

    /**
 * 查找匹配的{@code My}{@code Batis}工具审批。
 *
 * @param taskId 任务编号
 * @param action 动作
 * @param approvalId 审批编号
 * @return 可选的{@code My}{@code Batis}工具审批；不存在时返回空值
 */
    @Override
    public Optional<ToolApprovalRecord> find(String taskId,
                                             DestructiveToolAction action,
                                             String approvalId) {
        validateIdentity(taskId, action, approvalId);
        return Optional.ofNullable(mapper.selectOne(taskId, action.value(), approvalId)).map(this::toRecord);
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
    @Override
    public ToolApprovalRecord attachInvocationCheckpoint(String taskId,
                                                         DestructiveToolAction action,
                                                         String approvalId,
                                                         ToolInvocationCheckpoint checkpoint) {
        validateIdentity(taskId, action, approvalId);
        validateCheckpoint(checkpoint);
        String checkpointJson = serializeCheckpoint(checkpoint);
        int changed = mapper.attachInvocationCheckpoint(
                taskId,
                action.value(),
                approvalId,
                checkpoint.requestId(),
                checkpoint.toolName(),
                checkpoint.argumentsDigest(),
                checkpointJson,
                toLocal(checkpoint.capturedAt())
        );
        ToolApprovalRecord persisted = find(taskId, action, approvalId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.OPERATION_ERROR, "工具审批请求不存在，无法保存调用断点"));
        if (changed != 1 && !Objects.equals(persisted.invocationCheckpoint(), checkpoint)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "工具审批调用断点与已有记录冲突");
        }
        if (!Objects.equals(persisted.invocationCheckpoint(), checkpoint)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "工具审批调用断点持久化失败");
        }
        return persisted;
    }

    /**
 * 审批并返回。
 *
 * @param taskId 任务编号
 * @param action 动作
 * @param approvalId 审批编号
 * @param decidedBy {@code decidedBy} 对应的调用参数
 * @param decidedAt {@code decidedAt} 对应的调用参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean approve(String taskId,
                           DestructiveToolAction action,
                           String approvalId,
                           Long decidedBy,
                           Instant decidedAt) {
        validateDecision(taskId, action, approvalId, decidedBy, decidedAt);
        return mapper.approve(taskId, action.value(), approvalId, decidedBy, toLocal(decidedAt)) == 1;
    }

    /**
 * 拒绝{@code My}{@code Batis}工具审批并记录原因。
 *
 * @param taskId 任务编号
 * @param action 动作
 * @param approvalId 审批编号
 * @param decidedBy {@code decidedBy} 对应的调用参数
 * @param decidedAt {@code decidedAt} 对应的调用参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean reject(String taskId,
                          DestructiveToolAction action,
                          String approvalId,
                          Long decidedBy,
                          Instant decidedAt) {
        validateDecision(taskId, action, approvalId, decidedBy, decidedAt);
        return mapper.reject(taskId, action.value(), approvalId, decidedBy, toLocal(decidedAt)) == 1;
    }

    /**
 * 开始执行。
 *
 * @param taskId 任务编号
 * @param action 动作
 * @param approvalId 审批编号
 * @param toolRequestId 工具请求编号
 * @param executionStartedAt {@code executionStartedAt} 对应的调用参数
 * @param maxAttempts 待处理的 {@code maxAttempts} 集合
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean beginExecution(String taskId,
                                  DestructiveToolAction action,
                                  String approvalId,
                                  String toolRequestId,
                                  Instant executionStartedAt,
                                  int maxAttempts) {
        validateIdentity(taskId, action, approvalId);
        if (toolRequestId == null || toolRequestId.isBlank()
                || executionStartedAt == null || maxAttempts <= 0) {
            throw new IllegalArgumentException("tool execution start identity is incomplete");
        }
        return mapper.beginExecution(taskId, action.value(), approvalId, toolRequestId,
                toLocal(executionStartedAt), maxAttempts) == 1;
    }

    /**
 * 完成执行并持久化终态。
 *
 * @param taskId 任务编号
 * @param action 动作
 * @param approvalId 审批编号
 * @param toolRequestId 工具请求编号
 * @param outcome 结果
 * @param consumedAt {@code consumedAt} 对应的调用参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean completeExecution(String taskId,
                                     DestructiveToolAction action,
                                     String approvalId,
                                     String toolRequestId,
                                     ToolExecutionOutcome outcome,
                                     Instant consumedAt) {
        validateIdentity(taskId, action, approvalId);
        if (toolRequestId == null || toolRequestId.isBlank() || outcome == null || consumedAt == null) {
            throw new IllegalArgumentException("tool execution completion is incomplete");
        }
        String serializedOutcome = serializeOutcome(outcome);
        return mapper.completeExecution(taskId, action.value(), approvalId, toolRequestId,
                serializedOutcome, toLocal(consumedAt)) == 1;
    }

    /**
 * 查找匹配的{@code Recoverable}执行。
 *
 * @param taskId 任务编号
 * @return 可选的{@code Recoverable}执行；不存在时返回空值
 */
    @Override
    public Optional<ToolApprovalRecord> findRecoverableExecution(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("tool approval task identity is invalid");
        }
        return Optional.ofNullable(mapper.selectRecoverableExecution(taskId)).map(this::toRecord);
    }

    /**
 * 返回{@code reset}{@code Stale}执行。
 *
 * @param taskId 任务编号
 * @param action 动作
 * @param approvalId 审批编号
 * @param expectedVersion {@code expectedVersion} 对应的调用参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean resetStaleExecution(String taskId,
                                       DestructiveToolAction action,
                                       String approvalId,
                                       long expectedVersion) {
        validateIdentity(taskId, action, approvalId);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("tool approval version is invalid");
        }
        return mapper.resetStaleExecution(
                taskId, action.value(), approvalId, expectedVersion) == 1;
    }

    /**
 * 返回{@code expire}执行前。
 *
 * @param now 当前时间
 * @param limit 资源上限
 * @return 计算或处理后的数值结果
 */
    @Override
    public int expireBefore(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit <= 0 || limit > MAX_EXPIRE_BATCH) {
            throw new IllegalArgumentException("approval expiration batch size is invalid");
        }
        return mapper.expireBefore(toLocal(now), limit);
    }

    /**
 * 查找匹配的{@code Waiting}{@code Continuations}。
 *
 * @param limit 资源上限
 * @return {@code Waiting}{@code Continuations}集合
 */
    @Override
    public List<ToolApprovalRecord> findWaitingContinuations(int limit) {
        validateBatchLimit(limit);
        List<GenerationToolApproval> approvals = mapper.selectWaitingContinuations(limit);
        if (approvals == null) {
            return List.of();
        }
        return approvals.stream().filter(Objects::nonNull).map(this::toRecord).toList();
    }

    /** 将当前对象转换为{@code Entity}。 */
    private GenerationToolApproval toEntity(ToolApprovalRecord approval) {
        ToolInvocationCheckpoint checkpoint = approval.invocationCheckpoint();
        return GenerationToolApproval.builder()
                .approvalId(approval.approvalId())
                .taskId(approval.taskId())
                .appId(approval.appId())
                .userId(approval.userId())
                .action(approval.action().value())
                .requestJson(approval.requestJson())
                .status(approval.status().value())
                .requestedAt(toLocal(approval.requestedAt()))
                .expiresAt(toLocal(approval.expiresAt()))
                .toolRequestId(checkpoint == null ? null : checkpoint.requestId())
                .toolName(checkpoint == null ? null : checkpoint.toolName())
                .argumentsDigest(checkpoint == null ? null : checkpoint.argumentsDigest())
                .checkpointJson(checkpoint == null ? null : serializeCheckpoint(checkpoint))
                .executionStartedAt(toLocal(approval.executionStartedAt()))
                .executionResult(approval.executionOutcome() == null
                        ? null : serializeOutcome(approval.executionOutcome()))
                .executionAttempt(approval.executionAttempt())
                .version(approval.version())
                .build();
    }

    /** 将当前对象转换为记录。 */
    private ToolApprovalRecord toRecord(GenerationToolApproval entity) {
        DestructiveToolAction action = DestructiveToolAction.fromValue(entity.getAction());
        ToolApprovalStatus status = ToolApprovalStatus.fromValue(entity.getStatus());
        if (action == null || status == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "工具审批数据状态不合法");
        }
        return new ToolApprovalRecord(
                entity.getApprovalId(), entity.getTaskId(), entity.getAppId(), entity.getUserId(),
                action, entity.getRequestJson(), status, toInstant(entity.getRequestedAt()),
                toInstant(entity.getExpiresAt()), entity.getDecidedBy(), toInstant(entity.getDecidedAt()),
                toInstant(entity.getConsumedAt()), entity.getVersion() == null ? 0 : entity.getVersion(),
                toCheckpoint(entity), toInstant(entity.getExecutionStartedAt()),
                toOutcome(entity.getExecutionResult()),
                entity.getExecutionAttempt() == null ? 0 : entity.getExecutionAttempt()
        );
    }

    /** 将当前对象转换为检查点。 */
    private ToolInvocationCheckpoint toCheckpoint(GenerationToolApproval entity) {
        boolean absent = entity.getToolRequestId() == null
                && entity.getToolName() == null
                && entity.getArgumentsDigest() == null
                && entity.getCheckpointJson() == null;
        if (absent) {
            return null;
        }
        if (entity.getToolRequestId() == null || entity.getToolName() == null
                || entity.getArgumentsDigest() == null || entity.getCheckpointJson() == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "工具审批调用断点数据不完整");
        }
        ToolInvocationCheckpoint checkpoint;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            var json = JSONUtil.parseObj(entity.getCheckpointJson());
            checkpoint = new ToolInvocationCheckpoint(
                    json.getInt("schemaVersion", 0),
                    json.getStr("requestId"),
                    json.getStr("toolName"),
                    json.getStr("argumentsJson", ""),
                    json.getStr("runtimeStateJson", ""),
                    Instant.parse(json.getStr("capturedAt"))
            );
        } catch (RuntimeException malformedCheckpoint) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "工具审批调用断点无法解析", malformedCheckpoint);
        }
        validateCheckpoint(checkpoint);
        if (!Objects.equals(entity.getToolRequestId(), checkpoint.requestId())
                || !Objects.equals(entity.getToolName(), checkpoint.toolName())
                || !Objects.equals(entity.getArgumentsDigest(), checkpoint.argumentsDigest())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "工具审批调用断点摘要校验失败");
        }
        return checkpoint;
    }

    private String serializeCheckpoint(ToolInvocationCheckpoint checkpoint) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("schemaVersion", checkpoint.schemaVersion());
        json.put("requestId", checkpoint.requestId());
        json.put("toolName", checkpoint.toolName());
        json.put("argumentsJson", checkpoint.argumentsJson());
        json.put("runtimeStateJson", checkpoint.runtimeStateJson());
        json.put("capturedAt", checkpoint.capturedAt().toString());
        return JSONUtil.toJsonStr(json);
    }

    private String serializeOutcome(ToolExecutionOutcome outcome) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("error", outcome.error());
        String resultText = outcome.resultText();
        if (resultText.length() > MAX_EXECUTION_RESULT_CHARS) {
            resultText = resultText.substring(0, MAX_EXECUTION_RESULT_CHARS);
        }
        json.put("resultText", resultText);
        json.put("mutationEvidencePresent", outcome.mutationEvidencePresent());
        json.put("effectiveMutationPaths", outcome.effectiveMutationPaths());
        return JSONUtil.toJsonStr(json);
    }

    /** 将当前对象转换为结果。 */
    private ToolExecutionOutcome toOutcome(String executionResult) {
        if (executionResult == null) {
            return null;
        }
        try {
            var json = JSONUtil.parseObj(executionResult);
            boolean mutationEvidencePresent =
                    Boolean.TRUE.equals(json.getBool("mutationEvidencePresent"));
            return new ToolExecutionOutcome(
                    Boolean.TRUE.equals(json.getBool("error")),
                    json.getStr("resultText", ""),
                    mutationEvidencePresent,
                    parseEffectiveMutationPaths(json, mutationEvidencePresent)
            );
        } catch (RuntimeException malformedOutcome) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR, "工具执行结果无法解析", malformedOutcome);
        }
    }

    private List<String> parseEffectiveMutationPaths(
            cn.hutool.json.JSONObject json,
            boolean mutationEvidencePresent
    ) {
        if (!mutationEvidencePresent) {
            return List.of();
        }
        cn.hutool.json.JSONArray values = json.getJSONArray("effectiveMutationPaths");
        if (values == null) {
            throw new IllegalArgumentException("工具执行结果缺少有效变更路径字段");
        }
        List<String> paths = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof String path)) {
                throw new IllegalArgumentException("工具执行结果包含非法变更路径");
            }
            paths.add(path);
        }
        return List.copyOf(paths);
    }

    private boolean sameRequest(ToolApprovalRecord left, ToolApprovalRecord right) {
        return Objects.equals(left.taskId(), right.taskId())
                && Objects.equals(left.appId(), right.appId())
                && Objects.equals(left.userId(), right.userId())
                && left.action() == right.action()
                && Objects.equals(left.requestJson(), right.requestJson())
                && Objects.equals(left.invocationCheckpoint(), right.invocationCheckpoint());
    }

    /** 校验{@code ate}记录是否有效。 */
    private void validateRecord(ToolApprovalRecord approval) {
        Objects.requireNonNull(approval, "approval");
        validateIdentity(approval.taskId(), approval.action(), approval.approvalId());
        if (approval.appId() == null || approval.appId() <= 0
                || approval.userId() == null || approval.userId() <= 0
                || approval.status() != ToolApprovalStatus.PENDING
                || approval.requestedAt() == null || approval.expiresAt() == null
                || !approval.expiresAt().isAfter(approval.requestedAt())) {
            throw new IllegalArgumentException("tool approval request is incomplete");
        }
        if (approval.invocationCheckpoint() != null) {
            validateCheckpoint(approval.invocationCheckpoint());
        }
    }

    private void validateDecision(String taskId,
                                  DestructiveToolAction action,
                                  String approvalId,
                                  Long decidedBy,
                                  Instant decidedAt) {
        validateIdentity(taskId, action, approvalId);
        if (decidedBy == null || decidedBy <= 0 || decidedAt == null) {
            throw new IllegalArgumentException("tool approval decision identity is incomplete");
        }
    }

    /** 校验{@code ate}检查点是否有效。 */
    private void validateCheckpoint(ToolInvocationCheckpoint checkpoint) {
        if (checkpoint == null
                || checkpoint.schemaVersion() != ToolInvocationCheckpoint.CURRENT_SCHEMA_VERSION
                || checkpoint.requestId() == null || checkpoint.requestId().isBlank()
                || checkpoint.requestId().length() > 128
                || checkpoint.toolName() == null
                || !checkpoint.toolName().matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")
                || checkpoint.argumentsJson().length() > MAX_TOOL_ARGUMENTS_CHARS
                || checkpoint.runtimeStateJson().isBlank()
                || checkpoint.runtimeStateJson().length() > MAX_RUNTIME_CHECKPOINT_CHARS
                || checkpoint.capturedAt() == null) {
            throw new IllegalArgumentException("tool invocation checkpoint is invalid");
        }
    }

    private void validateBatchLimit(int limit) {
        if (limit <= 0 || limit > MAX_EXPIRE_BATCH) {
            throw new IllegalArgumentException("approval continuation batch size is invalid");
        }
    }

    private void validateIdentity(String taskId,
                                  DestructiveToolAction action,
                                  String approvalId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")
                || action == null || approvalId == null || !approvalId.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("tool approval identity is invalid");
        }
    }

    private LocalDateTime toLocal(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, databaseZone);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(databaseZone).toInstant();
    }
}
