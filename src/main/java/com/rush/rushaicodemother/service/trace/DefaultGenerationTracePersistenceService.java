package com.rush.rushaicodemother.service.trace;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.GenerationTraceMapper;
import com.rush.rushaicodemother.model.entity.GenerationBuildLog;
import com.rush.rushaicodemother.model.entity.GenerationModelCall;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.GenerationModelUsageSource;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** 生成追踪持久化边界的 MyBatis 实现。 */
@Repository
@RequiredArgsConstructor
public class DefaultGenerationTracePersistenceService implements GenerationTracePersistenceService {

    private final GenerationTraceMapper mapper;

    @Override
    public boolean insertTask(NewTask task) {
        requireTask(task);
        GenerationTask entity = GenerationTask.builder()
                .taskId(task.taskId())
                .appId(task.appId())
                .userId(task.userId())
                .originalCodeGenType(task.originalCodeGenType())
                .targetCodeGenType(task.targetCodeGenType())
                .status(GenerationTaskStatus.RUNNING.getValue())
                .stage("start")
                .stageMessage(null)
                .userPrompt(task.userPrompt())
                .enhancedPrompt(task.enhancedPrompt())
                .requiresBuildValidation(task.requiresBuildValidation() ? 1 : 0)
                .qualityGate(task.qualityGate())
                .orchestrationMode(task.orchestrationMode())
                .startTime(task.startTime())
                .createTime(task.startTime())
                .updateTime(task.startTime())
                .build();
        try {
            requireOneAffectedRow(mapper.insertTask(entity), "创建生成任务 trace");
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public TaskRecord findTaskByTaskId(String taskId) {
        return toTaskRecord(mapper.selectTaskByTaskId(requireText(taskId, "生成任务 ID")));
    }

    @Override
    public TaskRecord lockTaskByTaskId(String taskId) {
        return toTaskRecord(mapper.selectTaskByTaskIdForUpdate(requireText(taskId, "生成任务 ID")));
    }

    @Override
    public boolean enrichRuntimeTaskTrace(long recordId, NewTask task, LocalDateTime updateTime) {
        requirePositive(recordId, "生成任务记录 ID");
        requireTask(task);
        requireTime(updateTime, "trace 更新时间");
        return mapper.enrichRunningTaskTrace(
                recordId, task.originalCodeGenType(), task.targetCodeGenType(),
                task.userPrompt(), task.enhancedPrompt(), task.requiresBuildValidation() ? 1 : 0,
                task.qualityGate(), task.orchestrationMode(), updateTime) == 1;
    }

    @Override
    public void updateRunningTaskStage(long recordId,
                                       String stage,
                                       String stageMessage,
                                       LocalDateTime updateTime) {
        requirePositive(recordId, "生成任务记录 ID");
        requireText(stage, "生成阶段");
        requireTime(updateTime, "阶段更新时间");
        requireOneAffectedRow(
                mapper.updateRunningTaskStage(recordId, stage, stageMessage, updateTime),
                "更新生成任务阶段"
        );
    }

    @Override
    public void updateTaskMemorySummary(long recordId, String memorySummary, LocalDateTime updateTime) {
        requirePositive(recordId, "生成任务记录 ID");
        requireTime(updateTime, "记忆摘要更新时间");
        requireOneAffectedRow(
                mapper.updateTaskMemorySummary(recordId, memorySummary, updateTime),
                "更新生成任务记忆摘要"
        );
    }

    @Override
    public void completeRunningTask(long recordId,
                                    GenerationTaskStatus status,
                                    LocalDateTime endTime,
                                    long durationMs,
                                    String errorMessage) {
        requirePositive(recordId, "生成任务记录 ID");
        if (status == null || !status.isTerminal()) {
            throw invalid("生成任务终态不合法");
        }
        requireTime(endTime, "生成任务结束时间");
        if (durationMs < 0) {
            throw invalid("生成任务耗时不合法");
        }
        requireOneAffectedRow(
                mapper.completeRunningTask(recordId, status.getValue(), endTime, durationMs, errorMessage),
                "完成生成任务 trace"
        );
    }

    @Override
    public void insertBuildLog(NewBuildLog buildLog) {
        requireBuildLog(buildLog);
        GenerationBuildLog entity = GenerationBuildLog.builder()
                .taskId(buildLog.taskId())
                .appId(buildLog.appId())
                .userId(buildLog.userId())
                .projectPath(buildLog.projectPath())
                .stage(buildLog.stage())
                .success(buildLog.success() ? 1 : 0)
                .summary(buildLog.summary())
                .report(buildLog.report())
                .qualityGate(buildLog.qualityGate())
                .willAutoRepair(buildLog.willAutoRepair() ? 1 : 0)
                .createTime(buildLog.createTime())
                .build();
        requireOneAffectedRow(mapper.insertBuildLog(entity), "记录生成构建日志");
    }

    @Override
    public boolean insertModelCall(NewModelCall modelCall) {
        requireModelCall(modelCall);
        GenerationModelCall entity = GenerationModelCall.builder()
                .callId(modelCall.callId())
                .taskId(modelCall.taskId())
                .appId(modelCall.appId())
                .userId(modelCall.userId())
                .provider(modelCall.provider())
                .model(modelCall.model())
                .promptTokens(modelCall.promptTokens())
                .completionTokens(modelCall.completionTokens())
                .totalTokens(modelCall.totalTokens())
                .latencyMs(modelCall.latencyMs())
                .finishReason(modelCall.finishReason())
                .usageSource(modelCall.usageSource().name())
                .createTime(modelCall.createTime())
                .build();
        try {
            requireOneAffectedRow(mapper.insertModelCall(entity), "记录生成模型调用");
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    @Override
    public ModelCallRecord findModelCallByCallId(String callId) {
        GenerationModelCall entity = mapper.selectModelCallByCallId(requireText(callId, "模型调用 ID"));
        return entity == null ? null : toModelCallRecord(entity);
    }

    @Override
    public List<TaskRecord> listRecentTasksByAppId(long appId, int limit) {
        requirePositive(appId, "应用 ID");
        return safeList(mapper.selectRecentTasksByAppId(appId, limit)).stream()
                .map(this::toTaskRecord)
                .toList();
    }

    @Override
    public List<BuildLogRecord> listRecentBuildLogsByAppId(long appId, int limit) {
        requirePositive(appId, "应用 ID");
        return safeList(mapper.selectRecentBuildLogsByAppId(appId, limit)).stream()
                .map(this::toBuildLogRecord)
                .toList();
    }

    @Override
    public List<BuildLogRecord> listBuildLogsByTaskId(String taskId, int limit) {
        return safeList(mapper.selectBuildLogsByTaskId(requireText(taskId, "生成任务 ID"), limit)).stream()
                .map(this::toBuildLogRecord)
                .toList();
    }

    private TaskRecord toTaskRecord(GenerationTask entity) {
        if (entity == null) {
            return null;
        }
        GenerationTaskStatus status = GenerationTaskStatus.fromValue(entity.getStatus());
        if (!hasPositiveId(entity.getId())
                || !hasPositiveId(entity.getAppId())
                || !hasPositiveId(entity.getUserId())
                || entity.getTaskId() == null || entity.getTaskId().isBlank()
                || status == null
                || entity.getStartTime() == null
                || entity.getCreateTime() == null) {
            throw corrupted("生成任务 trace 数据不完整");
        }
        return new TaskRecord(
                entity.getId(), entity.getTaskId(), entity.getAppId(), entity.getUserId(),
                entity.getOriginalCodeGenType(), entity.getTargetCodeGenType(), status,
                entity.getStage(), entity.getStageMessage(), entity.getUserPrompt(), entity.getEnhancedPrompt(),
                Integer.valueOf(1).equals(entity.getRequiresBuildValidation()),
                entity.getQualityGate(), entity.getOrchestrationMode(), entity.getStartTime(),
                entity.getEndTime(), entity.getDurationMs(), entity.getErrorMessage(),
                entity.getMemorySummary(), entity.getCreateTime()
        );
    }

    private ModelCallRecord toModelCallRecord(GenerationModelCall entity) {
        GenerationModelUsageSource usageSource;
        try {
            usageSource = GenerationModelUsageSource.valueOf(entity.getUsageSource());
        } catch (RuntimeException exception) {
            throw corrupted("生成模型调用 usageSource 不合法");
        }
        if (entity.getCallId() == null || entity.getCallId().isBlank()
                || entity.getTaskId() == null || entity.getTaskId().isBlank()
                || !hasPositiveId(entity.getAppId()) || !hasPositiveId(entity.getUserId())) {
            throw corrupted("生成模型调用数据不完整");
        }
        return new ModelCallRecord(
                entity.getCallId(), entity.getTaskId(), entity.getAppId(), entity.getUserId(),
                entity.getProvider(), entity.getModel(), entity.getPromptTokens(),
                entity.getCompletionTokens(), entity.getTotalTokens(), entity.getLatencyMs(),
                entity.getFinishReason(), usageSource
        );
    }

    private BuildLogRecord toBuildLogRecord(GenerationBuildLog entity) {
        if (entity == null || entity.getTaskId() == null || entity.getTaskId().isBlank()
                || entity.getCreateTime() == null || entity.getSuccess() == null) {
            throw corrupted("生成构建日志数据不完整");
        }
        return new BuildLogRecord(
                entity.getTaskId(), entity.getStage(), entity.getSuccess() == 1,
                entity.getSummary(), entity.getReport(), entity.getCreateTime()
        );
    }

    private void requireTask(NewTask task) {
        if (task == null || task.startTime() == null
                || task.taskId() == null || task.taskId().isBlank()
                || task.appId() <= 0 || task.userId() <= 0) {
            throw invalid("生成任务 trace 参数不完整");
        }
    }

    private void requireBuildLog(NewBuildLog buildLog) {
        if (buildLog == null || buildLog.createTime() == null
                || buildLog.taskId() == null || buildLog.taskId().isBlank()
                || buildLog.appId() <= 0 || buildLog.userId() <= 0) {
            throw invalid("生成构建日志参数不完整");
        }
    }

    private void requireModelCall(NewModelCall modelCall) {
        if (modelCall == null || modelCall.createTime() == null || modelCall.usageSource() == null
                || modelCall.callId() == null || modelCall.callId().isBlank()
                || modelCall.taskId() == null || modelCall.taskId().isBlank()
                || modelCall.appId() <= 0 || modelCall.userId() <= 0) {
            throw invalid("生成模型调用参数不完整");
        }
    }

    private void requireOneAffectedRow(int affectedRows, String operation) {
        if (affectedRows != 1) {
            throw corrupted(operation + "失败，数据库影响行数异常: " + affectedRows);
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw invalid(fieldName + "不能为空");
        }
        return value;
    }

    private void requirePositive(long value, String fieldName) {
        if (value <= 0) {
            throw invalid(fieldName + "不合法");
        }
    }

    private void requireTime(LocalDateTime value, String fieldName) {
        if (value == null) {
            throw invalid(fieldName + "不能为空");
        }
    }

    private boolean hasPositiveId(Long value) {
        return value != null && value > 0;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.PARAMS_ERROR, message);
    }

    private BusinessException corrupted(String message) {
        return new BusinessException(ErrorCode.OPERATION_ERROR, message);
    }
}
