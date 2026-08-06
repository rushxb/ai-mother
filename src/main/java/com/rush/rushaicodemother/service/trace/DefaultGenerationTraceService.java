package com.rush.rushaicodemother.service.trace;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationModelCallStatus;
import com.rush.rushaicodemother.model.enums.GenerationModelUsageSource;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.service.trace.GenerationTracePersistenceService.BuildLogRecord;
import com.rush.rushaicodemother.service.trace.GenerationTracePersistenceService.ModelCallRecord;
import com.rush.rushaicodemother.service.trace.GenerationTracePersistenceService.NewBuildLog;
import com.rush.rushaicodemother.service.trace.GenerationTracePersistenceService.NewModelCall;
import com.rush.rushaicodemother.service.trace.GenerationTracePersistenceService.NewTask;
import com.rush.rushaicodemother.service.trace.GenerationTracePersistenceService.TaskRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 生成任务、构建日志和模型调用的生产级追踪实现。 */
@Slf4j
@Service
public class DefaultGenerationTraceService implements GenerationTraceService {

    private static final int MAX_TASK_ID_LENGTH = 128;
    private static final int MAX_STAGE_LENGTH = 64;
    private static final int MAX_STAGE_MESSAGE_LENGTH = 2_000;
    private static final int MAX_MEMORY_SUMMARY_LENGTH = 6_000;
    private static final int MAX_PROMPT_LENGTH = 1_000_000;
    private static final int MAX_QUALITY_GATE_LENGTH = 64;
    private static final int MAX_ORCHESTRATION_MODE_LENGTH = 64;
    private static final int MAX_PROJECT_PATH_LENGTH = 1_024;
    private static final int MAX_BUILD_SUMMARY_LENGTH = 4_000;
    private static final int MAX_BUILD_REPORT_LENGTH = 12_000;
    private static final int MAX_PROVIDER_LENGTH = 64;
    private static final int MAX_MODEL_LENGTH = 128;
    private static final int MAX_FINISH_REASON_LENGTH = 64;
    private static final int MAX_PROVIDER_REQUEST_ID_LENGTH = 128;
    private static final int MAX_ERROR_CATEGORY_LENGTH = 64;
    private static final int MAX_MODEL_METADATA_LENGTH = 12_000;
    private static final int SHA_256_HEX_LENGTH = 64;
    private static final int MAX_QUERY_LIMIT = 20;

    private final GenerationTracePersistenceService persistenceService;
    private final GenerationExecutionContextService executionContextService;
    private final Clock clock;

    @Autowired
    public DefaultGenerationTraceService(GenerationTracePersistenceService persistenceService,
                                         GenerationExecutionContextService executionContextService) {
        this(persistenceService, executionContextService, Clock.systemDefaultZone());
    }

    DefaultGenerationTraceService(GenerationTracePersistenceService persistenceService, Clock clock) {
        this(persistenceService, null, clock);
    }

    DefaultGenerationTraceService(GenerationTracePersistenceService persistenceService,
                                  GenerationExecutionContextService executionContextService,
                                  Clock clock) {
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService");
        this.executionContextService = executionContextService;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional
    public void startTask(GenerationTaskStartCommand command) {
        startTask(command, false);
    }

    @Override
    @Transactional
    public GenerationTaskTraceStartResult startOrTransitionTask(GenerationTaskStartCommand command) {
        return startTask(command, true);
    }

    /** 启动任务。 */
    private GenerationTaskTraceStartResult startTask(GenerationTaskStartCommand command,
                                                      boolean allowRunningTransition) {
        NewTask task = normalizeStartCommand(command);
        if (persistenceService.insertTask(task)) {
            log.info("生成任务 trace 已创建，taskId: {}, appId: {}, userId: {}, targetType: {}, orchestrationMode: {}",
                    task.taskId(), task.appId(), task.userId(), task.targetCodeGenType(), task.orchestrationMode());
            return GenerationTaskTraceStartResult.STARTED;
        }
        TaskRecord existing = persistenceService.findTaskByTaskId(task.taskId());
        if (existing == null) {
            throw operationFailed("生成任务 trace 并发创建后无法读取，taskId=" + task.taskId());
        }
        if (isRuntimeShell(existing)) {
            if (!sameTaskIdentity(existing, task)) {
                throw operationFailed("生成任务 ID 已被不同请求占用，taskId=" + task.taskId());
            }
            if (persistenceService.enrichRuntimeTaskTrace(
                    existing.recordId(), task, executionFence(task.taskId()), LocalDateTime.now(clock))) {
                return GenerationTaskTraceStartResult.STARTED;
            }
            existing = persistenceService.findTaskByTaskId(task.taskId());
        }
        if (existing != null && sameTaskPayload(existing, task)) {
            return GenerationTaskTraceStartResult.REUSED;
        }
        if (!allowRunningTransition) {
            throw taskIdentityConflict(task.taskId());
        }
        TaskRecord locked = persistenceService.lockTaskByTaskId(task.taskId());
        if (locked != null && sameTaskPayload(locked, task)) {
            return GenerationTaskTraceStartResult.REUSED;
        }
        if (!canTransitionRunningTask(locked, task)) {
            throw taskIdentityConflict(task.taskId());
        }
        persistenceService.transitionRunningTaskTrace(
                locked.recordId(), task, executionFence(task.taskId()), LocalDateTime.now(clock));
        log.info("生成任务 trace 路由已迁移，taskId: {}, orchestrationMode: {}, targetType: {}",
                task.taskId(), task.orchestrationMode(), task.targetCodeGenType());
        return GenerationTaskTraceStartResult.TRANSITIONED;
    }

    /**
 * 更新阶段。
 *
 * @param taskId 任务编号
 * @param stage 阶段
 * @param stageMessage 阶段消息
 */
    @Override
    @Transactional
    public void updateStage(String taskId, String stage, String stageMessage) {
        String normalizedTaskId = requireText(taskId, MAX_TASK_ID_LENGTH, "生成任务 ID");
        String normalizedStage = requireText(stage, MAX_STAGE_LENGTH, "生成阶段");
        String normalizedMessage = truncate(nullableText(stageMessage), MAX_STAGE_MESSAGE_LENGTH);
        TaskRecord task = requireLockedTask(normalizedTaskId);
        if (task.status() != GenerationTaskStatus.RUNNING) {
            throw operationFailed("终态生成任务不允许更新阶段，taskId=" + normalizedTaskId
                    + ", status=" + task.status().getValue());
        }
        if (Objects.equals(task.stage(), normalizedStage)
                && Objects.equals(task.stageMessage(), normalizedMessage)) {
            return;
        }
        persistenceService.updateRunningTaskStage(
                task.recordId(), normalizedStage, normalizedMessage,
                executionFence(normalizedTaskId), LocalDateTime.now(clock));
    }

    /**
 * 更新记忆汇总。
 *
 * @param taskId 任务编号
 * @param memorySummary 记忆汇总
 */
    @Override
    @Transactional
    public void updateMemorySummary(String taskId, String memorySummary) {
        String normalizedTaskId = requireText(taskId, MAX_TASK_ID_LENGTH, "生成任务 ID");
        String normalizedSummary = truncate(nullableText(memorySummary), MAX_MEMORY_SUMMARY_LENGTH);
        TaskRecord task = requireLockedTask(normalizedTaskId);
        if (Objects.equals(task.memorySummary(), normalizedSummary)) {
            return;
        }
        persistenceService.updateTaskMemorySummary(
                task.recordId(), normalizedSummary,
                executionFence(normalizedTaskId), LocalDateTime.now(clock));
    }

    /**
 * 完成任务并持久化终态。
 *
 * @param taskId 任务编号
 * @param status 目标状态
 * @param errorMessage 错误消息
 */
    @Override
    @Transactional
    public void completeTask(String taskId, GenerationTaskStatus status, String errorMessage) {
        completeTaskInternal(taskId, status, errorMessage, null);
    }

    /**
 * 完成任务并记录结果质量证据。
 *
 * @param taskId 任务编号
 * @param status 目标状态
 * @param errorMessage 错误消息
 * @param outcomeQuality 结果质量证据，允许为空
 */
    @Override
    @Transactional
    public void completeTask(String taskId,
                             GenerationTaskStatus status,
                             String errorMessage,
                             GenerationOutcomeQuality outcomeQuality) {
        completeTaskInternal(taskId, status, errorMessage, outcomeQuality);
    }

    /**
     * 终态写入的唯一实现。
     *
     * <p>结果质量证据为空时调用既有 6 参数持久化方法，保持原协作路径不变；
     * 仅在确有证据时才走带证据的重载，避免为新增能力改变既有调用契约。</p>
     */
    private void completeTaskInternal(String taskId,
                                      GenerationTaskStatus status,
                                      String errorMessage,
                                      GenerationOutcomeQuality outcomeQuality) {
        String normalizedTaskId = requireText(taskId, MAX_TASK_ID_LENGTH, "生成任务 ID");
        if (status == null || !status.isTerminal()) {
            throw invalid("生成任务完成状态必须是终态");
        }
        TaskRecord task = requireLockedTask(normalizedTaskId);
        if (task.status().isTerminal()) {
            if (task.status() == status) {
                return;
            }
            throw operationFailed("生成任务终态冲突，taskId=" + normalizedTaskId
                    + ", persisted=" + task.status().getValue()
                    + ", requested=" + status.getValue());
        }
        if (task.status() != GenerationTaskStatus.RUNNING) {
            throw operationFailed("生成任务当前状态不允许完成，taskId=" + normalizedTaskId);
        }
        LocalDateTime endTime = LocalDateTime.now(clock);
        long durationMs = Duration.between(task.startTime(), endTime).toMillis();
        if (durationMs < 0) {
            throw operationFailed("生成任务结束时间早于数据库开始时间，taskId=" + normalizedTaskId);
        }
        String normalizedError = truncate(nullableText(errorMessage), MAX_STAGE_MESSAGE_LENGTH);
        if (outcomeQuality == null || outcomeQuality.isEmpty()) {
            persistenceService.completeRunningTask(
                    task.recordId(), status, endTime, durationMs, normalizedError,
                    executionFence(normalizedTaskId));
        } else {
            persistenceService.completeRunningTask(
                    task.recordId(), status, endTime, durationMs, normalizedError,
                    executionFence(normalizedTaskId), outcomeQuality);
        }
        log.info("生成任务 trace 已完成，taskId: {}, status: {}, durationMs: {}",
                normalizedTaskId, status.getValue(), durationMs);
    }

    /**
 * 记录事件相关指标或状态。
 *
 * @param taskId 任务编号
 * @param appId 应用编号
 * @param userId 用户编号
 * @param event 待处理的领域事件
 */
    @Override
    public void recordEvent(String taskId, Long appId, Long userId, GenerationStreamEvent event) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (event == null || !GenerationStreamEvent.BUILD_RESULT.equals(event.getType())) {
            return;
        }
        String normalizedTaskId = requireText(taskId, MAX_TASK_ID_LENGTH, "生成任务 ID");
        long normalizedAppId = requirePositiveId(appId, "应用 ID");
        long normalizedUserId = requirePositiveId(userId, "用户 ID");
        Map<String, Object> data = event.getData();
        if (data == null) {
            throw invalid("构建结果事件数据不能为空");
        }
        Boolean success = booleanValue(data.get("success"));
        if (success == null) {
            throw invalid("构建结果 success 不合法");
        }
        String stage = requireText(objectText(data.get("stage")), MAX_STAGE_LENGTH, "构建阶段");
        String report = nullableText(objectText(data.get("report")));
        if (report == null) {
            report = nullableText(event.getText());
        }
        NewBuildLog buildLog = new NewBuildLog(
                normalizedTaskId,
                normalizedAppId,
                normalizedUserId,
                truncate(nullableText(objectText(data.get("projectPath"))), MAX_PROJECT_PATH_LENGTH),
                stage,
                success,
                truncate(nullableText(objectText(data.get("summary"))), MAX_BUILD_SUMMARY_LENGTH),
                truncate(report, MAX_BUILD_REPORT_LENGTH),
                truncate(nullableText(objectText(data.get("qualityGate"))), MAX_QUALITY_GATE_LENGTH),
                Boolean.TRUE.equals(booleanValue(data.get("willAutoRepair"))),
                LocalDateTime.now(clock)
        );
        persistenceService.insertBuildLog(buildLog);
    }

    /**
 * 记录模型调用相关指标或状态。
 *
 * @param command 命令
 */
    @Override
    @Transactional
    public void recordModelCall(GenerationModelCallCommand command) {
        NewModelCall modelCall = normalizeModelCall(command);
        if (persistenceService.insertModelCall(modelCall)) {
            return;
        }
        ModelCallRecord existing = persistenceService.findModelCallByCallId(modelCall.callId());
        if (existing == null) {
            throw operationFailed("模型调用并发写入后无法读取，callId=" + modelCall.callId());
        }
        if (!sameModelCallPayload(existing, modelCall)) {
            throw operationFailed("模型调用 ID 已被不同调用占用，callId=" + modelCall.callId());
        }
    }

    /**
 * 列出符合条件的{@code Recent}任务按应用编号。
 *
 * @param appId 应用编号
 * @param limit 资源上限
 * @return {@code Recent}任务按应用编号集合
 */
    @Override
    public List<GenerationTaskTrace> listRecentTasksByAppId(Long appId, int limit) {
        long normalizedAppId = requirePositiveId(appId, "应用 ID");
        return persistenceService.listRecentTasksByAppId(normalizedAppId, normalizeLimit(limit)).stream()
                .map(task -> new GenerationTaskTrace(
                        task.taskId(), task.status(), task.stage(), task.stageMessage(),
                        task.userPrompt(), task.memorySummary(), task.errorMessage(), task.createTime()))
                .toList();
    }

    /**
 * 列出符合条件的{@code Recent}构建{@code Logs}按应用编号。
 *
 * @param appId 应用编号
 * @param limit 资源上限
 * @return {@code Recent}构建{@code Logs}按应用编号集合
 */
    @Override
    public List<GenerationBuildTrace> listRecentBuildLogsByAppId(Long appId, int limit) {
        long normalizedAppId = requirePositiveId(appId, "应用 ID");
        return toBuildTraces(persistenceService.listRecentBuildLogsByAppId(
                normalizedAppId, normalizeLimit(limit)));
    }

    /**
 * 列出符合条件的构建{@code Logs}按任务编号。
 *
 * @param taskId 任务编号
 * @param limit 资源上限
 * @return 构建{@code Logs}按任务编号集合
 */
    @Override
    public List<GenerationBuildTrace> listBuildLogsByTaskId(String taskId, int limit) {
        String normalizedTaskId = requireText(taskId, MAX_TASK_ID_LENGTH, "生成任务 ID");
        return toBuildTraces(persistenceService.listBuildLogsByTaskId(
                normalizedTaskId, normalizeLimit(limit)));
    }

    /** 规范化开始命令。 */
    private NewTask normalizeStartCommand(GenerationTaskStartCommand command) {
        if (command == null) {
            throw invalid("生成任务 trace 命令不能为空");
        }
        String targetType = enumValue(command.targetType());
        if (targetType == null) {
            throw invalid("目标代码生成类型不能为空");
        }
        return new NewTask(
                requireText(command.taskId(), MAX_TASK_ID_LENGTH, "生成任务 ID"),
                requirePositiveId(command.appId(), "应用 ID"),
                requirePositiveId(command.userId(), "用户 ID"),
                enumValue(command.originalType()),
                targetType,
                requireText(command.userPrompt(), MAX_PROMPT_LENGTH, "用户提示词"),
                boundedNullable(command.enhancedPrompt(), MAX_PROMPT_LENGTH, "增强提示词"),
                command.requiresBuildValidation(),
                boundedNullable(command.qualityGate(), MAX_QUALITY_GATE_LENGTH, "质量门禁"),
                requireText(command.orchestrationMode(), MAX_ORCHESTRATION_MODE_LENGTH, "编排模式"),
                LocalDateTime.now(clock)
        );
    }

    /** 规范化模型调用。 */
    private NewModelCall normalizeModelCall(GenerationModelCallCommand command) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (command == null) {
            throw invalid("模型调用命令不能为空");
        }
        String callId;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            callId = UUID.fromString(command.callId()).toString();
        } catch (RuntimeException exception) {
            throw invalid("模型调用 ID 必须是 UUID");
        }
        if (command.latencyMs() == null || command.latencyMs() < 0) {
            throw invalid("模型调用耗时不合法");
        }
        GenerationModelCallStatus status = command.status();
        if (status == null) {
            throw invalid("模型调用状态不能为空");
        }
        GenerationModelUsageSource usageSource = command.usageSource();
        if (usageSource == null) {
            throw invalid("模型调用 token 来源不能为空");
        }
        Integer promptTokens = nullableNonNegative(command.promptTokens(), "输入 token 数");
        Integer completionTokens = nullableNonNegative(command.completionTokens(), "输出 token 数");
        Integer totalTokens = nullableNonNegative(command.totalTokens(), "总 token 数");
        validateTokenUsage(promptTokens, completionTokens, totalTokens, usageSource);
        String errorCategory = boundedNullable(
                command.errorCategory(), MAX_ERROR_CATEGORY_LENGTH, "错误分类");
        if (status == GenerationModelCallStatus.ERROR && errorCategory == null) {
            throw invalid("失败模型调用必须包含错误分类");
        }
        if (status == GenerationModelCallStatus.SUCCESS && errorCategory != null) {
            throw invalid("成功模型调用不能包含错误分类");
        }
        GenerationModelCallProvenance provenance = command.provenance();
        if (provenance == null) {
            throw invalid("模型调用 provenance 不能为空");
        }
        return new NewModelCall(
                callId,
                requireText(command.taskId(), MAX_TASK_ID_LENGTH, "生成任务 ID"),
                requirePositiveId(command.appId(), "应用 ID"),
                requirePositiveId(command.userId(), "用户 ID"),
                requireText(command.provider(), MAX_PROVIDER_LENGTH, "模型提供商"),
                requireText(command.model(), MAX_MODEL_LENGTH, "模型名称"),
                status,
                boundedNullable(command.providerRequestId(), MAX_PROVIDER_REQUEST_ID_LENGTH,
                        "提供商请求 ID"),
                promptTokens,
                completionTokens,
                totalTokens,
                command.latencyMs(),
                boundedNullable(command.finishReason(), MAX_FINISH_REASON_LENGTH, "结束原因"),
                usageSource,
                errorCategory,
                requireSha256(provenance.requestHash(), "请求哈希"),
                requireSha256(provenance.promptTemplateHash(), "系统提示哈希"),
                requireSha256(provenance.toolSchemaHash(), "工具 schema 哈希"),
                requireSha256(provenance.modelConfigHash(), "模型配置哈希"),
                requireNonNegative(provenance.requestMessageCount(), "请求消息数量"),
                requireNonNegative(provenance.toolCount(), "工具数量"),
                boundedNullable(provenance.rawMetadataJson(), MAX_MODEL_METADATA_LENGTH, "模型元数据"),
                LocalDateTime.now(clock)
        );
    }

    private TaskRecord requireLockedTask(String taskId) {
        TaskRecord task = persistenceService.lockTaskByTaskId(taskId);
        if (task == null) {
            throw operationFailed("生成任务 trace 不存在，taskId=" + taskId);
        }
        return task;
    }

    private GenerationExecutionFence executionFence(String taskId) {
        return executionContextService == null
                ? null
                : executionContextService.getExecutionFence(taskId).orElse(null);
    }

    private boolean isRuntimeShell(TaskRecord task) {
        return task != null
                && task.targetCodeGenType() == null
                && task.userPrompt() == null
                && task.orchestrationMode() == null;
    }

    private boolean sameTaskIdentity(TaskRecord existing, NewTask requested) {
        return existing.taskId().equals(requested.taskId())
                && existing.appId() == requested.appId()
                && existing.userId() == requested.userId();
    }

    /** 返回{@code same}任务载荷。 */
    private boolean sameTaskPayload(TaskRecord existing, NewTask requested) {
        return existing.taskId().equals(requested.taskId())
                && existing.appId() == requested.appId()
                && existing.userId() == requested.userId()
                && Objects.equals(existing.originalCodeGenType(), requested.originalCodeGenType())
                && Objects.equals(existing.targetCodeGenType(), requested.targetCodeGenType())
                && Objects.equals(existing.userPrompt(), requested.userPrompt())
                && Objects.equals(existing.enhancedPrompt(), requested.enhancedPrompt())
                && existing.requiresBuildValidation() == requested.requiresBuildValidation()
                && Objects.equals(existing.qualityGate(), requested.qualityGate())
                && Objects.equals(existing.orchestrationMode(), requested.orchestrationMode());
    }

    private boolean canTransitionRunningTask(TaskRecord existing, NewTask requested) {
        return existing != null
                && existing.status() == GenerationTaskStatus.RUNNING
                && sameTaskIdentity(existing, requested)
                && Objects.equals(existing.userPrompt(), requested.userPrompt());
    }

    private BusinessException taskIdentityConflict(String taskId) {
        return operationFailed("生成任务 ID 已被不同请求占用或当前状态不允许迁移，taskId=" + taskId);
    }

    /** 返回{@code same}模型调用载荷。 */
    private boolean sameModelCallPayload(ModelCallRecord existing, NewModelCall requested) {
        return existing.callId().equals(requested.callId())
                && existing.taskId().equals(requested.taskId())
                && existing.appId() == requested.appId()
                && existing.userId() == requested.userId()
                && Objects.equals(existing.provider(), requested.provider())
                && Objects.equals(existing.model(), requested.model())
                && existing.status() == requested.status()
                && Objects.equals(existing.providerRequestId(), requested.providerRequestId())
                && Objects.equals(existing.promptTokens(), requested.promptTokens())
                && Objects.equals(existing.completionTokens(), requested.completionTokens())
                && Objects.equals(existing.totalTokens(), requested.totalTokens())
                && Objects.equals(existing.latencyMs(), requested.latencyMs())
                && Objects.equals(existing.finishReason(), requested.finishReason())
                && existing.usageSource() == requested.usageSource()
                && Objects.equals(existing.errorCategory(), requested.errorCategory())
                && Objects.equals(existing.requestHash(), requested.requestHash())
                && Objects.equals(existing.promptTemplateHash(), requested.promptTemplateHash())
                && Objects.equals(existing.toolSchemaHash(), requested.toolSchemaHash())
                && Objects.equals(existing.modelConfigHash(), requested.modelConfigHash())
                && Objects.equals(existing.requestMessageCount(), requested.requestMessageCount())
                && Objects.equals(existing.toolCount(), requested.toolCount())
                && Objects.equals(existing.rawMetadataJson(), requested.rawMetadataJson());
    }

    private List<GenerationBuildTrace> toBuildTraces(List<BuildLogRecord> records) {
        return records.stream()
                .map(record -> new GenerationBuildTrace(
                        record.taskId(), record.stage(), record.success(),
                        record.summary(), record.report(), record.createTime()))
                .toList();
    }

    private int normalizeLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_QUERY_LIMIT);
    }

    private String enumValue(CodeGenTypeEnum value) {
        return value == null ? null : value.getValue();
    }

    private int requireNonNegative(Integer value, String fieldName) {
        if (value == null || value < 0) {
            throw invalid(fieldName + "不合法");
        }
        return value;
    }

    private Integer nullableNonNegative(Integer value, String fieldName) {
        if (value != null && value < 0) {
            throw invalid(fieldName + "不合法");
        }
        return value;
    }

    /** 校验{@code ate}令牌用量是否有效。 */
    private void validateTokenUsage(Integer promptTokens,
                                    Integer completionTokens,
                                    Integer totalTokens,
                                    GenerationModelUsageSource usageSource) {
        if (usageSource == GenerationModelUsageSource.UNAVAILABLE) {
            if (promptTokens != null || completionTokens != null || totalTokens != null) {
                throw invalid("token 来源不可用时不能伪造 token 数量");
            }
            return;
        }
        if (promptTokens == null || completionTokens == null || totalTokens == null) {
            throw invalid("已知 token 来源必须包含完整 token 数量");
        }
        if ((long) promptTokens + completionTokens != totalTokens) {
            throw invalid("总 token 数必须等于输入与输出 token 数之和");
        }
    }

    private String requireSha256(String value, String fieldName) {
        String normalized = requireText(value, SHA_256_HEX_LENGTH, fieldName);
        if (normalized.length() != SHA_256_HEX_LENGTH || !normalized.matches("[a-fA-F0-9]{64}")) {
            throw invalid(fieldName + "必须是 SHA-256 十六进制值");
        }
        return normalized.toLowerCase();
    }

    private long requirePositiveId(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw invalid(fieldName + "不合法");
        }
        return value;
    }

    /** 校验并返回有效的{@code Text}。 */
    private String requireText(String value, int maxLength, String fieldName) {
        String normalized = nullableText(value);
        if (normalized == null) {
            throw invalid(fieldName + "不能为空");
        }
        if (normalized.length() > maxLength) {
            throw invalid(fieldName + "长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private String boundedNullable(String value, int maxLength, String fieldName) {
        String normalized = nullableText(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw invalid(fieldName + "长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private String nullableText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String objectText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 返回{@code boolean}值。 */
    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number number) {
            int intValue = number.intValue();
            return intValue == 1 ? Boolean.TRUE : intValue == 0 ? Boolean.FALSE : null;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.PARAMS_ERROR, message);
    }

    private BusinessException operationFailed(String message) {
        return new BusinessException(ErrorCode.OPERATION_ERROR, message);
    }
}
