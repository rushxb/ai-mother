package com.rush.rushaicodemother.orchestration;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.agent.GenerationRoutingSupport;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationSpecificationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateResult;
import com.rush.rushaicodemother.orchestration.artifact.RollbackPoint;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentNode;
import com.rush.rushaicodemother.orchestration.dag.GenerationDagCheckpointRecoveryPolicy;
import com.rush.rushaicodemother.orchestration.dag.GenerationDagRunner;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTaskStore;
import com.rush.rushaicodemother.orchestration.planning.GenerationPlanningGraphRegistry;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationRollbackPointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 第二阶段 DAG 多智能体编排器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentGenerationOrchestrator implements GenerationOrchestrator {

    private final GenerationDagRunner dagRunner;
    private final GenerationOrchestrationTaskStore taskStore;
    private final GenerationPlanningGraphRegistry planningGraphRegistry;
    private final GenerationRoutingSupport routingSupport;
    private final GenerationOrchestrationMetricsCollector metricsCollector;
    private final GenerationRollbackPointService rollbackPointService;

    /**
 * 准备后续流程所需的智能体生成{@code Orchestrator}。
 *
 * @param request 请求参数
 * @return 智能体生成{@code Orchestrator}
 */
    @Override
    public GenerationOrchestrationResult prepare(GenerationOrchestrationRequest request) {
        Long appId = request.app() == null ? null : request.app().getId();
        GenerationOrchestrationTask task = restoreOrCreateTask(request, appId);
        boolean heavyPath = resolveOrchestrationMode(request, task);
        String orchestrationMode = heavyPath ? "heavy" : "light";
        task.setOrchestrationMode(orchestrationMode);
        if (request.app() != null && task.getUserId() == null) {
            task.setUserId(request.app().getUserId());
        }
        taskStore.save(task);
        metricsCollector.recordRun(orchestrationMode, "started");
        GenerationAgentContext context = new GenerationAgentContext(request, task, heavyPath);
        List<GenerationAgentNode> nodes = planningGraphRegistry.resolve(
                request.planningVariant(), heavyPath);
        List<GenerationStreamEvent> events = new ArrayList<>();
        events.add(orchestrationStartEvent(task.getTaskId(), heavyPath));
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            events.addAll(dagRunner.run(nodes, context));
            QualityGateResult gateResult = requireQualityGateResult(request, context);
            recordSummaryMetrics(context, gateResult);
            if (gateResult != null && !gateResult.passed()) {
                metricsCollector.recordRun(orchestrationMode, "quality_gate_failed");
                metricsCollector.recordTotalDuration(
                        orchestrationMode,
                        context.getTargetType() == null ? null : context.getTargetType().getValue(),
                        "quality_gate_failed",
                        Duration.ofMillis(sumDurations(context))
                );
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "质量门禁未通过：" + String.join("；", gateResult.blockers()));
            }
            CodeGenTypeEnum targetType = context.getTargetType() == null ? request.currentType() : context.getTargetType();
            attachRollbackPoint(request, context, targetType);
            String enhancedMessage = extractEnhancedPrompt(context.getArtifacts());
            events.add(orchestrationReadyEvent(task.getTaskId(), context, heavyPath));
            metricsCollector.recordRun(orchestrationMode, "success");
            metricsCollector.recordTotalDuration(
                    orchestrationMode,
                    targetType.getValue(),
                    "success",
                    Duration.ofMillis(sumDurations(context))
            );
            return new GenerationOrchestrationResult(
                    request.currentType(),
                    targetType,
                    request.currentType().canUpgradeTo(targetType),
                    request.generatingStage(),
                    enhancedMessage,
                    events,
                    new LinkedHashMap<>(context.getArtifacts()),
                    gateResult,
                    new LinkedHashMap<>(context.getTimings()),
                    task.getTaskId()
            );
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            metricsCollector.recordRun(orchestrationMode, "failed");
            metricsCollector.recordTotalDuration(
                    orchestrationMode,
                    context.getTargetType() == null ? request.currentType().getValue() : context.getTargetType().getValue(),
                    "failed",
                    Duration.ofMillis(sumDurations(context))
            );
            throw e;
        }
    }

    private GenerationOrchestrationTask restoreOrCreateTask(GenerationOrchestrationRequest request, Long appId) {
        if (StrUtil.isBlank(request.taskId())) {
            return taskStore.create(appId, request.userMessage());
        }
        return taskStore.load(appId, request.taskId())
                .map(task -> validateResumableTask(task, request))
                .orElseGet(() -> taskStore.create(request.taskId(), appId, request.userMessage()));
    }

    /** 校验{@code ate}{@code Resumable}任务是否有效。 */
    private GenerationOrchestrationTask validateResumableTask(GenerationOrchestrationTask task,
                                                               GenerationOrchestrationRequest request) {
        if (!taskStore.matchesRequest(task, request.userMessage())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "任务恢复请求与原始请求不一致");
        }
        GenerationDagCheckpointRecoveryPolicy.Assessment assessment =
                GenerationDagCheckpointRecoveryPolicy.assess(task);
        if (!assessment.automaticallyRecoverable()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, assessment.reason());
        }
        return task;
    }

    /** 根据当前上下文解析编排模式。 */
    private boolean resolveOrchestrationMode(GenerationOrchestrationRequest request,
                                             GenerationOrchestrationTask task) {
        if (StrUtil.isBlank(task.getOrchestrationMode())) {
            return routingSupport.shouldUseHeavyPath(request);
        }
        if ("heavy".equals(task.getOrchestrationMode())) {
            return true;
        }
        if ("light".equals(task.getOrchestrationMode())) {
            return false;
        }
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "任务检查点包含未知编排模式");
    }

    /** 为当前上下文附加回滚点。 */
    private void attachRollbackPoint(GenerationOrchestrationRequest request,
                                     GenerationAgentContext context,
                                     CodeGenTypeEnum targetType) {
        Optional<GenerationArtifact> existingRollbackPoint = context.getArtifact(RollbackPoint.KEY);
        if (existingRollbackPoint.isPresent()) {
            try {
                RollbackPoint.fromArtifact(
                        existingRollbackPoint.get(),
                        request.app().getId(),
                        context.getTask().getTaskId()
                );
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "回滚点检查点损坏，无法安全恢复生成任务",
                        exception
                );
            }
            return;
        }
        GenerationArtifact artifact = rollbackPointService.prepareRollbackPoint(
                request,
                targetType,
                context.getTask().getTaskId()
        );
        context.putArtifacts(List.of(artifact));
        context.getTask().getArtifacts().put(artifact.key(), artifact);
        taskStore.save(context.getTask());
    }

    /** 从输入中提取{@code Enhanced}提示词。 */
    private String extractEnhancedPrompt(Map<String, GenerationArtifact> artifacts) {
        GenerationSpecificationArtifact specification = requireGenerationSpecification(artifacts);
        if (!specification.hasExecutionPrompt()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "代码生成规范为空");
        }
        return specification.enhancedPrompt();
    }

    /** 返回编排开始事件。 */
    private GenerationStreamEvent orchestrationStartEvent(String taskId, boolean heavyPath) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "Orchestrator");
        data.put("stage", "dag");
        data.put("status", "running");
        data.put("summary", heavyPath ? "DAG 重型编排启动" : "DAG 轻量编排启动");
        data.put("taskId", taskId);
        data.put("dagNode", "orchestrator");
        data.put("durationMs", 0);
        data.put("orchestrationMode", heavyPath ? "heavy" : "light");
        return GenerationStreamEvent.agentEvent("", data);
    }

    /** 返回编排就绪事件。 */
    private GenerationStreamEvent orchestrationReadyEvent(String taskId, GenerationAgentContext context, boolean heavyPath) {
        long totalDuration = sumDurations(context);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "Orchestrator");
        data.put("stage", "dag");
        data.put("status", "done");
        data.put("summary", heavyPath ? "DAG 重型编排完成，准备进入代码生成" : "DAG 轻量编排完成，准备进入代码生成");
        data.put("taskId", taskId);
        data.put("dagNode", "orchestrator");
        data.put("durationMs", totalDuration);
        data.put("targetType", context.getTargetType().getValue());
        data.put("artifactCount", context.getArtifacts().size());
        data.put("qualityGate", context.getQualityGateResult() == null ? "unknown" : context.getQualityGateResult().level());
        data.put("orchestrationMode", heavyPath ? "heavy" : "light");
        return GenerationStreamEvent.agentEvent("", data);
    }

    /** 记录汇总{@code Metrics}相关指标或状态。 */
    private void recordSummaryMetrics(GenerationAgentContext context, QualityGateResult gateResult) {
        String orchestrationMode = context.getOrchestrationMode();
        boolean patchFirst = requireGenerationSpecification(context.getArtifacts()).patchFirst();
        boolean buildFixEnabled = artifactBoolean(context, "buildfix_plan", "enabled");
        String rollbackStrategy = artifactString(context, "change_plan", "rollbackStrategy");
        String contextMode = artifactString(context, "context_summary", "contextMode");
        int selectedFileCount = artifactListSize(context, "context_summary", "selectedFiles");
        int indexedFileCount = artifactInt(context, "context_summary", "indexedFileCount");
        int indexedSymbolCount = artifactInt(context, "context_summary", "indexedSymbolCount");
        int indexHitCount = artifactListSize(context, "context_summary", "indexHits");
        int contextChars = artifactString(context, "context_summary", "projectContext").length();
        metricsCollector.recordPatchFirstPlan(orchestrationMode, patchFirst);
        metricsCollector.recordBuildFixPlan(orchestrationMode, buildFixEnabled);
        metricsCollector.recordRollbackPlan(orchestrationMode, rollbackStrategy);
        metricsCollector.recordContextSnapshot(
                orchestrationMode,
                contextMode,
                selectedFileCount,
                indexedFileCount,
                indexedSymbolCount,
                indexHitCount,
                contextChars
        );
        if (gateResult != null) {
            metricsCollector.recordQualityGate(orchestrationMode, gateResult.passed(), gateResult.level());
        }
    }

    private GenerationSpecificationArtifact requireGenerationSpecification(
            Map<String, GenerationArtifact> artifacts) {
        GenerationArtifact generationSpec = artifacts.get(GenerationSpecificationArtifact.KEY);
        if (generationSpec == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "缺少代码生成规范");
        }
        try {
            return GenerationSpecificationArtifact.fromArtifact(generationSpec);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "代码生成规范已损坏，无法继续执行",
                    exception
            );
        }
    }

    /**
     * 规划方案包含 Review 时，缺失的门禁事实必须失败关闭。
     * NO_PLAN 是消融基线，按设计不执行 Review，因此允许没有门禁制品。
     */
    private QualityGateResult requireQualityGateResult(GenerationOrchestrationRequest request,
                                                       GenerationAgentContext context) {
        QualityGateResult gateResult = context.getQualityGateResult();
        if (request.planningVariant() == GenerationPlanningVariant.NO_PLAN) {
            return gateResult;
        }
        if (gateResult == null) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "质量门禁检查点缺失，无法安全恢复生成任务"
            );
        }
        return gateResult;
    }

    private long sumDurations(GenerationAgentContext context) {
        return context.getTimings().values().stream().mapToLong(Long::longValue).sum();
    }

    private boolean artifactBoolean(GenerationAgentContext context, String artifactKey, String payloadKey) {
        Object value = context.getArtifactValue(artifactKey, payloadKey);
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String artifactString(GenerationAgentContext context, String artifactKey, String payloadKey) {
        Object value = context.getArtifactValue(artifactKey, payloadKey);
        return value == null ? "" : String.valueOf(value);
    }

    /** 返回制品{@code Int}。 */
    private int artifactInt(GenerationAgentContext context, String artifactKey, String payloadKey) {
        Object value = context.getArtifactValue(artifactKey, payloadKey);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int artifactListSize(GenerationAgentContext context, String artifactKey, String payloadKey) {
        Object value = context.getArtifactValue(artifactKey, payloadKey);
        if (value instanceof List<?> listValue) {
            return listValue.size();
        }
        return 0;
    }
}
