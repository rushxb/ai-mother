package com.rush.rushaicodemother.orchestration;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.agent.ArchitectAgentNode;
import com.rush.rushaicodemother.orchestration.agent.BuildFixAgentNode;
import com.rush.rushaicodemother.orchestration.agent.CodeAgentNode;
import com.rush.rushaicodemother.orchestration.agent.ContextAgentNode;
import com.rush.rushaicodemother.orchestration.agent.GenerationRoutingSupport;
import com.rush.rushaicodemother.orchestration.agent.PlannerAgentNode;
import com.rush.rushaicodemother.orchestration.agent.ReviewAgentNode;
import com.rush.rushaicodemother.orchestration.agent.TemplateAgentNode;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentNode;
import com.rush.rushaicodemother.orchestration.dag.GenerationDagCheckpointRecoveryPolicy;
import com.rush.rushaicodemother.orchestration.dag.GenerationDagRunner;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTaskStore;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationRollbackPointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第二阶段 DAG 多智能体编排器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentGenerationOrchestrator implements GenerationOrchestrator {

    private final GenerationDagRunner dagRunner;
    private final GenerationOrchestrationTaskStore taskStore;
    private final PlannerAgentNode plannerAgentNode;
    private final TemplateAgentNode templateAgentNode;
    private final ContextAgentNode contextAgentNode;
    private final ArchitectAgentNode architectAgentNode;
    private final CodeAgentNode codeAgentNode;
    private final ReviewAgentNode reviewAgentNode;
    private final BuildFixAgentNode buildFixAgentNode;
    private final GenerationRoutingSupport routingSupport;
    private final GenerationOrchestrationMetricsCollector metricsCollector;
    private final GenerationRollbackPointService rollbackPointService;

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
        List<GenerationAgentNode> nodes = selectNodes(heavyPath);
        List<GenerationStreamEvent> events = new ArrayList<>();
        events.add(orchestrationStartEvent(task.getTaskId(), heavyPath));
        try {
            events.addAll(dagRunner.run(nodes, context));
            QualityGateResult gateResult = context.getQualityGateResult();
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

    private List<GenerationAgentNode> selectNodes(boolean heavyPath) {
        List<GenerationAgentNode> nodes = new ArrayList<>();
        nodes.add(plannerAgentNode);
        nodes.add(templateAgentNode);
        nodes.add(contextAgentNode);
        nodes.add(architectAgentNode);
        nodes.add(codeAgentNode);
        nodes.add(reviewAgentNode);
        if (heavyPath) {
            nodes.add(buildFixAgentNode);
        }
        return List.copyOf(nodes);
    }

    private void attachRollbackPoint(GenerationOrchestrationRequest request,
                                     GenerationAgentContext context,
                                     CodeGenTypeEnum targetType) {
        if (context.getArtifact("rollback_point").isPresent()) {
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

    private String extractEnhancedPrompt(Map<String, GenerationArtifact> artifacts) {
        GenerationArtifact generationSpec = artifacts.get("generation_spec");
        if (generationSpec == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "缺少代码生成规范");
        }
        Object prompt = generationSpec.payload().get("enhancedPrompt");
        if (prompt == null || StrUtil.isBlank(String.valueOf(prompt))) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "代码生成规范为空");
        }
        return String.valueOf(prompt);
    }

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

    private void recordSummaryMetrics(GenerationAgentContext context, QualityGateResult gateResult) {
        String orchestrationMode = context.getOrchestrationMode();
        boolean patchFirst = artifactBoolean(context, "generation_spec", "patchFirst");
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
