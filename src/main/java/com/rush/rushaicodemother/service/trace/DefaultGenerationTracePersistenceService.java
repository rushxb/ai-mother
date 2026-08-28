package com.rush.rushaicodemother.service.trace;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.GenerationTraceMapper;
import com.rush.rushaicodemother.model.entity.GenerationBuildLog;
import com.rush.rushaicodemother.model.entity.GenerationModelCall;
import com.rush.rushaicodemother.model.entity.GenerationModelPromptSelection;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.GenerationModelCallStatus;
import com.rush.rushaicodemother.model.enums.GenerationModelUsageSource;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.model.enums.ModelInvocationBillingMode;
import com.rush.rushaicodemother.model.enums.ModelInvocationPurpose;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
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

    /**
 * 返回{@code insert}任务。
 *
 * @param task 任务
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
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

    /**
 * 查找匹配的任务按任务编号。
 *
 * @param taskId 任务编号
 * @return 任务按任务编号
 */
    @Override
    public TaskRecord findTaskByTaskId(String taskId) {
        return toTaskRecord(mapper.selectTaskByTaskId(requireText(taskId, "生成任务 ID")));
    }

    /**
 * 返回锁任务按任务编号。
 *
 * @param taskId 任务编号
 * @return 默认生成追踪持久化
 */
    @Override
    public TaskRecord lockTaskByTaskId(String taskId) {
        return toTaskRecord(mapper.selectTaskByTaskIdForUpdate(requireText(taskId, "生成任务 ID")));
    }

    /**
 * 补全运行时任务追踪。
 *
 * @param recordId 记录编号
 * @param task 任务
 * @param fence 围栏
 * @param updateTime 更新时间
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean enrichRuntimeTaskTrace(long recordId,
                                          NewTask task,
                                          GenerationExecutionFence fence,
                                          LocalDateTime updateTime) {
        requirePositive(recordId, "生成任务记录 ID");
        requireTask(task);
        requireTime(updateTime, "trace 更新时间");
        return mapper.enrichRunningTaskTrace(
                recordId, task.originalCodeGenType(), task.targetCodeGenType(),
                task.userPrompt(), task.enhancedPrompt(), task.requiresBuildValidation() ? 1 : 0,
                task.qualityGate(), task.orchestrationMode(), leaseOwner(fence), executionEpoch(fence),
                updateTime) == 1;
    }

    /**
 * 推动运行中任务追踪完成状态转换。
 *
 * @param recordId 记录编号
 * @param task 任务
 * @param fence 围栏
 * @param updateTime 更新时间
 */
    @Override
    public void transitionRunningTaskTrace(long recordId,
                                           NewTask task,
                                           GenerationExecutionFence fence,
                                           LocalDateTime updateTime) {
        requirePositive(recordId, "生成任务记录 ID");
        requireTask(task);
        requireTime(updateTime, "trace 路由迁移时间");
        requireOneAffectedRow(
                mapper.transitionRunningTaskTrace(
                        recordId,
                        task.originalCodeGenType(),
                        task.targetCodeGenType(),
                        task.enhancedPrompt(),
                        task.requiresBuildValidation() ? 1 : 0,
                        task.qualityGate(),
                        task.orchestrationMode(),
                        leaseOwner(fence),
                        executionEpoch(fence),
                        updateTime
                ),
                "迁移运行中生成任务路由"
        );
    }

    /**
 * 更新运行中任务阶段。
 *
 * @param recordId 记录编号
 * @param stage 阶段
 * @param stageMessage 阶段消息
 * @param fence 围栏
 * @param updateTime 更新时间
 */
    @Override
    public void updateRunningTaskStage(long recordId,
                                       String stage,
                                       String stageMessage,
                                       GenerationExecutionFence fence,
                                       LocalDateTime updateTime) {
        requirePositive(recordId, "生成任务记录 ID");
        requireText(stage, "生成阶段");
        requireTime(updateTime, "阶段更新时间");
        requireOneAffectedRow(
                mapper.updateRunningTaskStage(
                        recordId, stage, stageMessage, leaseOwner(fence), executionEpoch(fence), updateTime),
                "更新生成任务阶段"
        );
    }

    /**
 * 更新任务记忆汇总。
 *
 * @param recordId 记录编号
 * @param memorySummary 记忆汇总
 * @param fence 围栏
 * @param updateTime 更新时间
 */
    @Override
    public void updateTaskMemorySummary(long recordId,
                                        String memorySummary,
                                        GenerationExecutionFence fence,
                                        LocalDateTime updateTime) {
        requirePositive(recordId, "生成任务记录 ID");
        requireTime(updateTime, "记忆摘要更新时间");
        requireOneAffectedRow(
                mapper.updateTaskMemorySummary(
                        recordId, memorySummary, leaseOwner(fence), executionEpoch(fence), updateTime),
                "更新生成任务记忆摘要"
        );
    }

    /**
 * 完成运行中任务并持久化终态。
 *
 * @param recordId 记录编号
 * @param status 目标状态
 * @param endTime {@code endTime} 对应的调用参数
 * @param durationMs 待处理的 {@code durationMs} 集合
 * @param errorMessage 错误消息
 * @param fence 围栏
 */
    @Override
    public void completeRunningTask(long recordId,
                                    GenerationTaskStatus status,
                                    LocalDateTime endTime,
                                    long durationMs,
                                    String errorMessage,
                                    GenerationExecutionFence fence) {
        completeRunningTask(recordId, status, endTime, durationMs, errorMessage, fence, null);
    }

    /**
 * 完成运行中任务并折叠写入结果质量证据。
 *
 * @param recordId 记录编号
 * @param status 目标状态
 * @param endTime 结束时间
 * @param durationMs 耗时毫秒
 * @param errorMessage 错误消息
 * @param fence 围栏
 * @param outcomeQuality 结果质量证据，允许为空
 */
    @Override
    public void completeRunningTask(long recordId,
                                    GenerationTaskStatus status,
                                    LocalDateTime endTime,
                                    long durationMs,
                                    String errorMessage,
                                    GenerationExecutionFence fence,
                                    GenerationOutcomeQuality outcomeQuality) {
        requirePositive(recordId, "生成任务记录 ID");
        if (status == null || !status.isTerminal()) {
            throw invalid("生成任务终态不合法");
        }
        requireTime(endTime, "生成任务结束时间");
        if (durationMs < 0) {
            throw invalid("生成任务耗时不合法");
        }
        GenerationOutcomeQuality quality = outcomeQuality == null
                ? GenerationOutcomeQuality.empty()
                : outcomeQuality;
        requireOneAffectedRow(
                mapper.completeRunningTask(
                        recordId, status.getValue(), endTime, durationMs, errorMessage, null,
                        leaseOwner(fence), executionEpoch(fence),
                        quality.thinkingMode(),
                        quality.changedFileCount(),
                        quality.firstBuildPassedValue(),
                        quality.repairRounds(),
                        quality.firstPreviewMillis(),
                        quality.failureCategory(),
                        quality.reworkedAt(), quality.distilledAt(),
                        null, null, null, null),
                "完成生成任务 trace"
        );
    }

    /**
 * 处理{@code insert}构建日志。
 *
 * @param buildLog 构建日志
 */
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

    /**
 * 返回{@code insert}模型调用。
 *
 * @param modelCall 模型调用
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean insertModelCall(NewModelCall modelCall) {
        requireModelCall(modelCall);
        GenerationModelCall entity = GenerationModelCall.builder()
                .callId(modelCall.callId())
                .taskId(modelCall.taskId())
                .appId(modelCall.appId())
                .userId(modelCall.userId())
                .invocationPurpose(modelCall.invocationPurpose().name())
                .billingMode(modelCall.billingMode().name())
                .billingExemptionReason(modelCall.billingExemptionReason())
                .provider(modelCall.provider())
                .model(modelCall.model())
                .callStatus(modelCall.status().name())
                .providerRequestId(modelCall.providerRequestId())
                .promptTokens(modelCall.promptTokens())
                .completionTokens(modelCall.completionTokens())
                .totalTokens(modelCall.totalTokens())
                .latencyMs(modelCall.latencyMs())
                .finishReason(modelCall.finishReason())
                .usageSource(modelCall.usageSource().name())
                .errorCategory(modelCall.errorCategory())
                .requestHash(modelCall.requestHash())
                .promptTemplateHash(modelCall.promptTemplateHash())
                .toolSchemaHash(modelCall.toolSchemaHash())
                .modelConfigHash(modelCall.modelConfigHash())
                .requestMessageCount(modelCall.requestMessageCount())
                .toolCount(modelCall.toolCount())
                .rawMetadataJson(modelCall.rawMetadataJson())
                .createTime(modelCall.createTime())
                .build();
        try {
            requireOneAffectedRow(mapper.insertModelCall(entity), "记录生成模型调用");
        } catch (DuplicateKeyException exception) {
            return false;
        }
        for (GenerationPromptSelectionProvenance selection : modelCall.promptSelections()) {
            GenerationModelPromptSelection selectionEntity =
                    GenerationModelPromptSelection.builder()
                            .callId(modelCall.callId())
                            .taskId(modelCall.taskId())
                            .promptKey(selection.promptKey())
                            .promptVersion(selection.version())
                            .channel(selection.channel())
                            .contentHash(selection.contentHash())
                            .bundleId(selection.bundleId())
                            .createTime(modelCall.createTime())
                            .build();
            // 主调用已成功插入后，子事实写入失败必须抛出并触发同一事务回滚，不能伪装成幂等重复。
            requireOneAffectedRow(
                    mapper.insertModelPromptSelection(selectionEntity),
                    "记录模型调用 Prompt 版本");
        }
        return true;
    }

    /**
 * 查找匹配的模型调用按调用编号。
 *
 * @param callId 调用编号
 * @return 模型调用按调用编号
 */
    @Override
    public ModelCallRecord findModelCallByCallId(String callId) {
        GenerationModelCall entity = mapper.selectModelCallByCallId(requireText(callId, "模型调用 ID"));
        return entity == null ? null : toModelCallRecord(
                entity, mapper.selectModelPromptSelectionsByCallId(entity.getCallId()));
    }

    @Override
    public void completeStartedModelCall(NewModelCall modelCall) {
        requireModelCall(modelCall);
        if (modelCall.status() == GenerationModelCallStatus.STARTED) {
            throw invalid("模型调用完成状态不能为 STARTED");
        }
        GenerationModelCall entity = GenerationModelCall.builder()
                .callId(modelCall.callId())
                .taskId(modelCall.taskId())
                .appId(modelCall.appId())
                .userId(modelCall.userId())
                .invocationPurpose(modelCall.invocationPurpose().name())
                .billingMode(modelCall.billingMode().name())
                .billingExemptionReason(modelCall.billingExemptionReason())
                .provider(modelCall.provider())
                .model(modelCall.model())
                .callStatus(modelCall.status().name())
                .providerRequestId(modelCall.providerRequestId())
                .promptTokens(modelCall.promptTokens())
                .completionTokens(modelCall.completionTokens())
                .totalTokens(modelCall.totalTokens())
                .latencyMs(modelCall.latencyMs())
                .finishReason(modelCall.finishReason())
                .usageSource(modelCall.usageSource().name())
                .errorCategory(modelCall.errorCategory())
                .requestHash(modelCall.requestHash())
                .modelConfigHash(modelCall.modelConfigHash())
                .build();
        requireOneAffectedRow(mapper.completeStartedModelCall(entity), "完成生成模型调用账本");
    }

    @Override
    public int recoverStaleGenerationStartedModelCalls(LocalDateTime cutoff,
                                                        LocalDateTime observedAt) {
        if (cutoff == null || observedAt == null || cutoff.isAfter(observedAt)) {
            throw invalid("生成模型调用账本恢复时间不合法");
        }
        int affectedRows = mapper.recoverStaleGenerationStartedModelCalls(cutoff, observedAt);
        if (affectedRows < 0) {
            throw corrupted("恢复生成模型调用账本影响行数异常");
        }
        return affectedRows;
    }

    @Override
    public int recoverStaleExemptStartedModelCalls(LocalDateTime cutoff) {
        if (cutoff == null) {
            throw invalid("外围模型调用账本恢复截止时间不能为空");
        }
        int affectedRows = mapper.recoverStaleExemptStartedModelCalls(cutoff);
        if (affectedRows < 0) {
            throw corrupted("恢复外围模型调用账本影响行数异常");
        }
        return affectedRows;
    }

    @Override
    public long countStartedModelCalls() {
        long count = mapper.countStartedModelCalls();
        if (count < 0) {
            throw corrupted("未结算模型调用数量不合法");
        }
        return count;
    }

    /**
 * 列出符合条件的{@code Recent}任务按应用编号。
 *
 * @param appId 应用编号
 * @param limit 资源上限
 * @return {@code Recent}任务按应用编号集合
 */
    @Override
    public List<TaskRecord> listRecentTasksByAppId(long appId, int limit) {
        requirePositive(appId, "应用 ID");
        return safeList(mapper.selectRecentTasksByAppId(appId, limit)).stream()
                .map(this::toTaskRecord)
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
    public List<BuildLogRecord> listRecentBuildLogsByAppId(long appId, int limit) {
        requirePositive(appId, "应用 ID");
        return safeList(mapper.selectRecentBuildLogsByAppId(appId, limit)).stream()
                .map(this::toBuildLogRecord)
                .toList();
    }

    /**
 * 列出符合条件的构建{@code Logs}按任务编号。
 *
 * @param taskId 任务编号
 * @param limit 资源上限
 * @return 构建{@code Logs}按任务编号集合
 */
    @Override
    public List<BuildLogRecord> listBuildLogsByTaskId(String taskId, int limit) {
        return safeList(mapper.selectBuildLogsByTaskId(requireText(taskId, "生成任务 ID"), limit)).stream()
                .map(this::toBuildLogRecord)
                .toList();
    }

    /** 将当前对象转换为任务记录。 */
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

    /** 将当前对象转换为模型调用记录。 */
    private ModelCallRecord toModelCallRecord(
            GenerationModelCall entity,
            List<GenerationModelPromptSelection> promptSelections
    ) {
        GenerationModelUsageSource usageSource;
        GenerationModelCallStatus status;
        ModelInvocationPurpose invocationPurpose;
        ModelInvocationBillingMode billingMode;
        try {
            usageSource = GenerationModelUsageSource.valueOf(entity.getUsageSource());
            status = GenerationModelCallStatus.valueOf(entity.getCallStatus().toUpperCase());
            invocationPurpose = ModelInvocationPurpose.valueOf(entity.getInvocationPurpose());
            billingMode = ModelInvocationBillingMode.valueOf(entity.getBillingMode());
        } catch (RuntimeException exception) {
            throw corrupted("生成模型调用枚举字段不合法");
        }
        if (entity.getCallId() == null || entity.getCallId().isBlank()
                || entity.getTaskId() == null || entity.getTaskId().isBlank()
                || !hasPositiveId(entity.getUserId())
                || (invocationPurpose == ModelInvocationPurpose.GENERATION
                    && !hasPositiveId(entity.getAppId()))) {
            throw corrupted("生成模型调用数据不完整");
        }
        return new ModelCallRecord(
                entity.getCallId(), entity.getTaskId(), entity.getAppId(), entity.getUserId(),
                invocationPurpose, billingMode, entity.getBillingExemptionReason(),
                entity.getProvider(), entity.getModel(), status, entity.getProviderRequestId(),
                entity.getPromptTokens(),
                entity.getCompletionTokens(), entity.getTotalTokens(), entity.getLatencyMs(),
                entity.getFinishReason(), usageSource, entity.getErrorCategory(),
                entity.getRequestHash(), entity.getPromptTemplateHash(), entity.getToolSchemaHash(),
                entity.getModelConfigHash(), entity.getRequestMessageCount(), entity.getToolCount(),
                entity.getRawMetadataJson(),
                promptSelections == null ? List.of() : promptSelections.stream()
                        .map(selection -> new GenerationPromptSelectionProvenance(
                                selection.getPromptKey(),
                                selection.getPromptVersion(),
                                selection.getChannel(),
                                selection.getContentHash(),
                                selection.getBundleId()))
                        .toList()
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
                || modelCall.status() == null
                || modelCall.callId() == null || modelCall.callId().isBlank()
                || modelCall.taskId() == null || modelCall.taskId().isBlank()
                || modelCall.userId() <= 0
                || modelCall.invocationPurpose() == null || modelCall.billingMode() == null
                || (modelCall.invocationPurpose() == ModelInvocationPurpose.GENERATION
                    && !hasPositiveId(modelCall.appId()))) {
            throw invalid("生成模型调用参数不完整");
        }
    }

    private void requireOneAffectedRow(int affectedRows, String operation) {
        if (affectedRows != 1) {
            throw corrupted(operation + "失败，数据库影响行数异常: " + affectedRows);
        }
    }

    private String leaseOwner(GenerationExecutionFence fence) {
        return fence == null ? null : fence.leaseOwner();
    }

    private long executionEpoch(GenerationExecutionFence fence) {
        return fence == null ? 0L : fence.executionEpoch();
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
