package com.yupi.yuaicodemother.orchestration;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.core.handler.GenerationStreamEvent;
import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.agent.ArchitectAgentNode;
import com.yupi.yuaicodemother.orchestration.agent.BuildFixAgentNode;
import com.yupi.yuaicodemother.orchestration.agent.CodeAgentNode;
import com.yupi.yuaicodemother.orchestration.agent.ContextAgentNode;
import com.yupi.yuaicodemother.orchestration.agent.PlannerAgentNode;
import com.yupi.yuaicodemother.orchestration.agent.ReviewAgentNode;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.artifact.QualityGateResult;
import com.yupi.yuaicodemother.orchestration.dag.GenerationAgentContext;
import com.yupi.yuaicodemother.orchestration.dag.GenerationAgentNode;
import com.yupi.yuaicodemother.orchestration.dag.GenerationDagRunner;
import com.yupi.yuaicodemother.orchestration.dag.GenerationOrchestrationTask;
import com.yupi.yuaicodemother.orchestration.dag.GenerationOrchestrationTaskStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
    private final ContextAgentNode contextAgentNode;
    private final ArchitectAgentNode architectAgentNode;
    private final CodeAgentNode codeAgentNode;
    private final ReviewAgentNode reviewAgentNode;
    private final BuildFixAgentNode buildFixAgentNode;

    @Override
    public GenerationOrchestrationResult prepare(GenerationOrchestrationRequest request) {
        GenerationOrchestrationTask task = taskStore.create(
                request.app() == null ? null : request.app().getId(),
                request.userMessage()
        );
        GenerationAgentContext context = new GenerationAgentContext(request, task);
        List<GenerationAgentNode> nodes = List.of(
                plannerAgentNode,
                contextAgentNode,
                architectAgentNode,
                codeAgentNode,
                reviewAgentNode,
                buildFixAgentNode
        );
        List<GenerationStreamEvent> events = new ArrayList<>();
        events.add(orchestrationStartEvent(task.getTaskId()));
        events.addAll(dagRunner.run(nodes, context));
        QualityGateResult gateResult = context.getQualityGateResult();
        if (gateResult != null && !gateResult.passed()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "质量门禁未通过：" + String.join("；", gateResult.blockers()));
        }
        String enhancedMessage = extractEnhancedPrompt(context.getArtifacts());
        CodeGenTypeEnum targetType = context.getTargetType() == null ? request.currentType() : context.getTargetType();
        events.add(orchestrationReadyEvent(task.getTaskId(), context));
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

    private GenerationStreamEvent orchestrationStartEvent(String taskId) {
        return GenerationStreamEvent.agentEvent("", Map.of(
                "agent", "Orchestrator",
                "stage", "dag",
                "status", "running",
                "summary", "DAG 多智能体编排启动",
                "taskId", taskId,
                "dagNode", "orchestrator",
                "durationMs", 0
        ));
    }

    private GenerationStreamEvent orchestrationReadyEvent(String taskId, GenerationAgentContext context) {
        long totalDuration = context.getTimings().values().stream().mapToLong(Long::longValue).sum();
        return GenerationStreamEvent.agentEvent("", Map.of(
                "agent", "Orchestrator",
                "stage", "dag",
                "status", "done",
                "summary", "DAG 编排完成，准备进入代码生成",
                "taskId", taskId,
                "dagNode", "orchestrator",
                "durationMs", totalDuration,
                "targetType", context.getTargetType().getValue(),
                "artifactCount", context.getArtifacts().size(),
                "qualityGate", context.getQualityGateResult() == null ? "unknown" : context.getQualityGateResult().level()
        ));
    }
}
