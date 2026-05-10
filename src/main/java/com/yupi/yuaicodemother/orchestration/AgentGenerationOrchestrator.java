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
import java.util.Locale;

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
        boolean heavyPath = requiresHeavyPath(request);
        List<GenerationAgentNode> nodes = selectNodes(heavyPath);
        List<GenerationStreamEvent> events = new ArrayList<>();
        events.add(orchestrationStartEvent(task.getTaskId(), heavyPath));
        events.addAll(dagRunner.run(nodes, context));
        QualityGateResult gateResult = context.getQualityGateResult();
        if (gateResult != null && !gateResult.passed()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "质量门禁未通过：" + String.join("；", gateResult.blockers()));
        }
        String enhancedMessage = extractEnhancedPrompt(context.getArtifacts());
        CodeGenTypeEnum targetType = context.getTargetType() == null ? request.currentType() : context.getTargetType();
        events.add(orchestrationReadyEvent(task.getTaskId(), context, heavyPath));
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

    private List<GenerationAgentNode> selectNodes(boolean heavyPath) {
        List<GenerationAgentNode> nodes = new ArrayList<>();
        nodes.add(plannerAgentNode);
        nodes.add(contextAgentNode);
        nodes.add(architectAgentNode);
        nodes.add(codeAgentNode);
        nodes.add(reviewAgentNode);
        if (heavyPath) {
            nodes.add(buildFixAgentNode);
        }
        return List.copyOf(nodes);
    }

    private boolean requiresHeavyPath(GenerationOrchestrationRequest request) {
        String normalizedMessage = StrUtil.blankToDefault(request.userMessage(), "").toLowerCase(Locale.ROOT);
        if (!request.hasGeneratedCode() && request.currentType() == CodeGenTypeEnum.VUE_PROJECT) {
            return true;
        }
        boolean complexSignal = containsAny(normalizedMessage,
                "vue", "组件", "路由", "router", "后台", "管理系统", "登录", "注册",
                "api", "接口", "状态管理", "pinia", "图表", "表单", "多页面", "工作台", "dashboard");
        if (!request.hasGeneratedCode() && complexSignal) {
            return true;
        }
        if (containsAny(normalizedMessage,
                "build", "构建", "打包", "编译", "测试", "lint", "校验", "发布", "npm", "vite", "工程化", "vue工程")) {
            return true;
        }
        boolean plannerWillRoute = request.currentType() != CodeGenTypeEnum.HTML || complexSignal;
        if (plannerWillRoute && request.currentType() != CodeGenTypeEnum.VUE_PROJECT && request.routingFunction() != null) {
            try {
                String routingPrompt = "请根据以下需求判断最适合的生成模式：\n" + request.userMessage();
                CodeGenTypeEnum routedType = request.routingFunction().apply(routingPrompt);
                return routedType == CodeGenTypeEnum.VUE_PROJECT && request.currentType() != CodeGenTypeEnum.VUE_PROJECT;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private boolean containsAny(String value, String... keywords) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
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
        long totalDuration = context.getTimings().values().stream().mapToLong(Long::longValue).sum();
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
}
