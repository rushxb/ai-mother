package com.yupi.yuaicodemother.orchestration;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.core.handler.GenerationStreamEvent;
import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 第一阶段智能体编排器。
 * <p>
 * 当前版本先把需求分析、上下文准备和生成策略规划拆成可观测步骤，
 * 后续可以把这些步骤替换成真正的 DAG 多智能体节点。
 */
@Slf4j
@Component
public class AgentGenerationOrchestrator implements GenerationOrchestrator {

    private static final List<String> COMPLEXITY_KEYWORDS = List.of(
            "vue", "组件", "路由", "router", "多页面", "页面跳转", "后台管理", "管理系统",
            "登录", "注册", "接口", "api", "状态管理", "pinia", "复杂", "工程"
    );

    @Override
    public GenerationOrchestrationResult prepare(GenerationOrchestrationRequest request) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<String> projectContextFuture = CompletableFuture.supplyAsync(() -> {
                if (!request.hasGeneratedCode()) {
                    return "";
                }
                return StrUtil.blankToDefault(request.projectContextSupplier().get(), "");
            }, executor);
            CompletableFuture<GenerationStrategy> strategyFuture = CompletableFuture.supplyAsync(
                    () -> analyzeGenerationStrategy(request), executor);
            CompletableFuture<CodeGenTypeEnum> routedTypeFuture = projectContextFuture.thenApplyAsync(
                    projectContext -> routeCodeGenType(request, projectContext), executor);

            String projectContext = projectContextFuture.join();
            GenerationStrategy strategy = strategyFuture.join();
            CodeGenTypeEnum routedType = routedTypeFuture.join();
            CodeGenTypeEnum targetType = CodeGenTypeEnum.max(request.currentType(), routedType);
            boolean upgradeRequired = request.currentType().canUpgradeTo(targetType);
            String enhancedMessage = buildEnhancedUserMessage(request, targetType, upgradeRequired, projectContext);
            List<GenerationStreamEvent> events = buildAgentEvents(request, targetType, upgradeRequired, strategy, projectContext);
            return new GenerationOrchestrationResult(
                    request.currentType(),
                    targetType,
                    upgradeRequired,
                    request.generatingStage(),
                    enhancedMessage,
                    events
            );
        }
    }

    private CodeGenTypeEnum routeCodeGenType(GenerationOrchestrationRequest request, String projectContext) {
        if (!shouldRouteCodeGenType(request)) {
            return request.currentType();
        }
        try {
            String routingPrompt = buildRoutingPrompt(request, projectContext);
            CodeGenTypeEnum routedType = request.routingFunction().apply(routingPrompt);
            return routedType == null ? request.currentType() : routedType;
        } catch (Exception e) {
            Long appId = request.app() == null ? null : request.app().getId();
            log.warn("智能体路由失败，沿用当前模式，appId: {}", appId, e);
            return request.currentType();
        }
    }

    private boolean shouldRouteCodeGenType(GenerationOrchestrationRequest request) {
        if (request.currentType() == CodeGenTypeEnum.VUE_PROJECT) {
            return false;
        }
        if (StrUtil.isBlank(request.userMessage())) {
            return false;
        }
        if (!request.hasGeneratedCode()) {
            return true;
        }
        String normalized = request.userMessage().toLowerCase();
        return COMPLEXITY_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private String buildRoutingPrompt(GenerationOrchestrationRequest request, String projectContext) {
        StringBuilder builder = new StringBuilder();
        builder.append("当前应用模式：")
                .append(request.currentType().getValue())
                .append("（")
                .append(request.currentType().getText())
                .append("）\n");
        builder.append("用户这次的新需求：\n")
                .append(request.userMessage());
        if (StrUtil.isNotBlank(projectContext)) {
            builder.append("\n\n")
                    .append(AppConstant.PROJECT_CONTEXT_MARKER)
                    .append("\n")
                    .append(projectContext);
        }
        builder.append("\n\n请判断：为了满足这次需求，是否需要升级到更复杂的代码模式。");
        return builder.toString();
    }

    private GenerationStrategy analyzeGenerationStrategy(GenerationOrchestrationRequest request) {
        String normalized = StrUtil.blankToDefault(request.userMessage(), "").toLowerCase();
        boolean complex = COMPLEXITY_KEYWORDS.stream().anyMatch(normalized::contains);
        boolean update = request.hasGeneratedCode();
        String intent = update ? "基于现有项目迭代" : "创建新项目";
        String qualityGate = request.currentType() == CodeGenTypeEnum.VUE_PROJECT || complex
                ? "生成后执行构建校验，失败后进入最小范围自动修复"
                : "生成后保持轻量校验，优先快速返回";
        return new GenerationStrategy(intent, complex, qualityGate);
    }

    private String buildEnhancedUserMessage(GenerationOrchestrationRequest request,
                                            CodeGenTypeEnum targetType,
                                            boolean upgradeRequired,
                                            String projectContext) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append(request.userMessage());
        if (upgradeRequired) {
            promptBuilder.append("\n\n")
                    .append("【模式升级要求】\n")
                    .append("当前应用原本使用 ")
                    .append(request.currentType().getText())
                    .append("，但这次需求复杂度已经升级，请将项目整体升级为 ")
                    .append(targetType.getText())
                    .append("。\n")
                    .append("必须保留并迁移已有页面能力、样式意图和业务内容，输出结果要以新模式可继续迭代的工程结构为准。");
        }
        if (StrUtil.isNotBlank(projectContext)) {
            promptBuilder.append("\n\n")
                    .append(AppConstant.PROJECT_CONTEXT_MARKER)
                    .append("\n")
                    .append("这是当前应用已生成的代码摘要。后续回答必须基于这些现有内容继续修改、恢复或说明，不能声称无法访问当前项目或无法还原上一版内容。\n\n")
                    .append(projectContext);
        }
        promptBuilder.append("\n\n【智能体编排策略】\n")
                .append("1. 先理解需求和现有项目边界，再选择必要的最小改动路径。\n")
                .append("2. 复杂任务按页面、组件、样式、交互和构建校验拆分处理。\n")
                .append("3. 优先复用现有文件结构和依赖，避免无关重写。");
        return promptBuilder.toString();
    }

    private List<GenerationStreamEvent> buildAgentEvents(GenerationOrchestrationRequest request,
                                                         CodeGenTypeEnum targetType,
                                                         boolean upgradeRequired,
                                                         GenerationStrategy strategy,
                                                         String projectContext) {
        List<GenerationStreamEvent> events = new ArrayList<>();
        events.add(agentEvent("需求分析智能体", "planning", "done",
                strategy.intent() + (strategy.complex() ? "，识别为复杂任务" : "，识别为轻量任务"),
                Map.of("parallelGroup", "prepare", "order", 1)));
        events.add(agentEvent("上下文智能体", "context", "done",
                StrUtil.isBlank(projectContext) ? "未发现可复用项目上下文，将按新需求生成" : "已提取项目索引和关键文件上下文",
                Map.of("parallelGroup", "prepare", "order", 2)));
        events.add(agentEvent("架构规划智能体", "architecture", "done",
                "生成模式：" + targetType.getText() + (upgradeRequired ? "，需要执行模式升级" : "，保持当前模式"),
                Map.of("targetType", targetType.getValue(), "upgradeRequired", upgradeRequired, "order", 3)));
        events.add(agentEvent("质量策略智能体", "quality", "done",
                strategy.qualityGate(),
                Map.of("order", 4)));
        events.add(agentEvent("代码生成智能体", "codegen", "running",
                "编排完成，开始调用代码生成执行器",
                Map.of("generationStage", request.generatingStage(), "order", 5)));
        return events;
    }

    private GenerationStreamEvent agentEvent(String agent,
                                             String stage,
                                             String status,
                                             String summary,
                                             Map<String, Object> extraData) {
        Map<String, Object> data = new java.util.HashMap<>(extraData);
        data.put("agent", agent);
        data.put("stage", stage);
        data.put("status", status);
        data.put("summary", summary);
        return GenerationStreamEvent.agentEvent("", data);
    }

    private record GenerationStrategy(String intent, boolean complex, String qualityGate) {
    }
}
