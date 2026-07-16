package com.rush.rushaicodemother.service.trace;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationModelUsageSource;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
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
    private static final int MAX_QUERY_LIMIT = 20;

    private final GenerationTracePersistenceService persistenceService;
    private final Clock clock;

    @Autowired
    public DefaultGenerationTraceService(GenerationTracePersistenceService persistenceService) {
        this(persistenceService, Clock.systemDefaultZone());
    }

    DefaultGenerationTraceService(GenerationTracePersistenceService persistenceService, Clock clock) {
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional
    public void startTask(GenerationTaskStartCommand command) {
        NewTask task = normalizeStartCommand(command);
        if (persistenceService.insertTask(task)) {
            log.info("生成任务 trace 已创建，taskId: {}, appId: {}, userId: {}, targetType: {}, orchestrationMode: {}",
                    task.taskId(), task.appId(), task.userId(), task.targetCodeGenType(), task.orchestrationMode());
            return;
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
                    existing.recordId(), task, LocalDateTime.now(clock))) {
                return;
            }
            existing = persistenceService.findTaskByTaskId(task.taskId());
        }
        if (existing == null || !sameTaskPayload(existing, task)) {
            throw operationFailed("生成任务 ID 已被不同请求占用，taskId=" + task.taskId());
        }
    }

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
                task.recordId(), normalizedStage, normalizedMessage, LocalDateTime.now(clock));
    }

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
                task.recordId(), normalizedSummary, LocalDateTime.now(clock));
    }

    @Override
    @Transactional
    public void completeTask(String taskId, GenerationTaskStatus status, String errorMessage) {
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
        persistenceService.completeRunningTask(
                task.recordId(), status, endTime, durationMs, normalizedError);
        log.info("生成任务 trace 已完成，taskId: {}, status: {}, durationMs: {}",
                normalizedTaskId, status.getValue(), durationMs);
    }

    @Override
    public void recordEvent(String taskId, Long appId, Long userId, GenerationStreamEvent event) {
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

    @Override
    public List<GenerationTaskTrace> listRecentTasksByAppId(Long appId, int limit) {
        long normalizedAppId = requirePositiveId(appId, "应用 ID");
        return persistenceService.listRecentTasksByAppId(normalizedAppId, normalizeLimit(limit)).stream()
                .map(task -> new GenerationTaskTrace(
                        task.taskId(), task.status(), task.stage(), task.stageMessage(),
                        task.userPrompt(), task.memorySummary(), task.errorMessage(), task.createTime()))
                .toList();
    }

    @Override
    public List<GenerationBuildTrace> listRecentBuildLogsByAppId(Long appId, int limit) {
        long normalizedAppId = requirePositiveId(appId, "应用 ID");
        return toBuildTraces(persistenceService.listRecentBuildLogsByAppId(
                normalizedAppId, normalizeLimit(limit)));
    }

    @Override
    public List<GenerationBuildTrace> listBuildLogsByTaskId(String taskId, int limit) {
        String normalizedTaskId = requireText(taskId, MAX_TASK_ID_LENGTH, "生成任务 ID");
        return toBuildTraces(persistenceService.listBuildLogsByTaskId(
                normalizedTaskId, normalizeLimit(limit)));
    }

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

    private NewModelCall normalizeModelCall(GenerationModelCallCommand command) {
        if (command == null) {
            throw invalid("模型调用命令不能为空");
        }
        String callId;
        try {
            callId = UUID.fromString(command.callId()).toString();
        } catch (RuntimeException exception) {
            throw invalid("模型调用 ID 必须是 UUID");
        }
        int promptTokens = requireNonNegative(command.promptTokens(), "输入 token 数");
        int completionTokens = requireNonNegative(command.completionTokens(), "输出 token 数");
        int totalTokens = requireNonNegative(command.totalTokens(), "总 token 数");
        long expectedTotal = (long) promptTokens + completionTokens;
        if (expectedTotal != totalTokens) {
            throw invalid("总 token 数必须等于输入与输出 token 数之和");
        }
        if (command.latencyMs() == null || command.latencyMs() < 0) {
            throw invalid("模型调用耗时不合法");
        }
        GenerationModelUsageSource usageSource = command.usageSource();
        if (usageSource == null) {
            throw invalid("模型调用 token 来源不能为空");
        }
        return new NewModelCall(
                callId,
                requireText(command.taskId(), MAX_TASK_ID_LENGTH, "生成任务 ID"),
                requirePositiveId(command.appId(), "应用 ID"),
                requirePositiveId(command.userId(), "用户 ID"),
                requireText(command.provider(), MAX_PROVIDER_LENGTH, "模型提供商"),
                requireText(command.model(), MAX_MODEL_LENGTH, "模型名称"),
                promptTokens,
                completionTokens,
                totalTokens,
                command.latencyMs(),
                boundedNullable(command.finishReason(), MAX_FINISH_REASON_LENGTH, "结束原因"),
                usageSource,
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

    private boolean sameModelCallPayload(ModelCallRecord existing, NewModelCall requested) {
        return existing.callId().equals(requested.callId())
                && existing.taskId().equals(requested.taskId())
                && existing.appId() == requested.appId()
                && existing.userId() == requested.userId()
                && Objects.equals(existing.provider(), requested.provider())
                && Objects.equals(existing.model(), requested.model())
                && Objects.equals(existing.promptTokens(), requested.promptTokens())
                && Objects.equals(existing.completionTokens(), requested.completionTokens())
                && Objects.equals(existing.totalTokens(), requested.totalTokens())
                && Objects.equals(existing.latencyMs(), requested.latencyMs())
                && Objects.equals(existing.finishReason(), requested.finishReason())
                && existing.usageSource() == requested.usageSource();
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

    private long requirePositiveId(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw invalid(fieldName + "不合法");
        }
        return value;
    }

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
