package com.rush.rushaicodemother.orchestration.tool;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.ai.tools.BaseTool;
import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.ai.tools.ToolRiskLevel;
import com.rush.rushaicodemother.config.AiAgentProductivityProperties;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 根据真实工作区写入进展约束低生产率的模型工具回合。 */
@Component
public class GenerationAgentProductivityGuard {

    static final String ACTION_FORCE = "force_action";
    static final String ACTION_FINALIZE = "finalize";
    static final String REASON_READ_ONLY = "read_only_stall";
    static final String REASON_NO_MUTATION = "no_mutation_turns";

    private static final String EXIT_TOOL = "exitTool";

    private final AiAgentProductivityProperties properties;
    private final ToolManager toolManager;
    private final GenerationExecutionContextService executionContextService;
    private final GenerationOrchestrationMetricsCollector metricsCollector;
    private final Cache<String, TaskState> states;

    public GenerationAgentProductivityGuard(
            AiAgentProductivityProperties properties,
            ToolManager toolManager,
            GenerationExecutionContextService executionContextService,
            GenerationOrchestrationMetricsCollector metricsCollector
    ) {
        if (properties == null || !properties.isConfigurationValid()) {
            throw new IllegalArgumentException("AI Agent 生产率治理配置无效");
        }
        this.properties = properties;
        this.toolManager = Objects.requireNonNull(toolManager, "工具管理器不能为空");
        this.executionContextService = Objects.requireNonNull(
                executionContextService, "生成执行上下文服务不能为空");
        this.metricsCollector = Objects.requireNonNull(metricsCollector, "生成指标收集器不能为空");
        this.states = Caffeine.newBuilder()
                .maximumSize(properties.getMaximumTrackedTasks())
                .expireAfterAccess(properties.getRetention())
                .build();
    }

    /** 对受管应用即将发出的模型回合应用生产率决策。 */
    public ChatRequest governModelTurn(Long appId, ChatRequest request) {
        GenerationExecutionContext context = executionContextService.getByAppId(appId).orElse(null);
        if (context == null) {
            return requireRequest(request);
        }
        return governModelTurn(
                context.taskId(), context.successfulWorkspaceMutationCount(), request);
    }

    /** 对已知任务即将发出的模型回合应用生产率决策。 */
    public ChatRequest governModelTurn(String taskId,
                                       int successfulWorkspaceMutations,
                                       ChatRequest request) {
        ChatRequest safeRequest = requireRequest(request);
        if (taskId == null || taskId.isBlank()) {
            return safeRequest;
        }
        TaskState state = states.get(taskId, ignored -> new TaskState());
        Decision decision = state.beforeModelTurn(
                successfulWorkspaceMutations,
                properties.getMaxReadOnlyCallsWithoutMutation(),
                properties.getMaxModelTurnsWithoutMutation(),
                properties.getForcedActionTurnsBeforeFinalize()
        );
        if (decision.action() == Action.NONE) {
            return safeRequest;
        }
        metricsCollector.recordAgentProductivityIntervention(
                decision.action().metricValue(), decision.reason());
        return decision.action() == Action.FINALIZE
                ? forceFinalResponse(safeRequest, decision)
                : forceWorkspaceAction(safeRequest, decision);
    }

    /** 在工具完成后记录本回合的能力类型；真实进展仍以 mutation checkpoint 为准。 */
    public void recordToolCompletion(String taskId, String toolName) {
        if (taskId == null || taskId.isBlank() || toolName == null || toolName.isBlank()
                || EXIT_TOOL.equals(toolName)) {
            return;
        }
        BaseTool tool = toolManager.getTool(toolName);
        if (tool == null || tool.getRiskLevel() == null) {
            return;
        }
        states.get(taskId, ignored -> new TaskState()).recordTool(tool.getRiskLevel());
    }

    /** 从人在回路审批携带的最近会话恢复低生产率窗口。 */
    public void restore(String taskId,
                        List<ChatMessage> messages,
                        int successfulWorkspaceMutations) {
        if (taskId == null || taskId.isBlank() || messages == null) {
            return;
        }
        TaskState restored = new TaskState();
        Map<String, ToolExecutionResultMessage> resultsById = new HashMap<>();
        for (ChatMessage message : messages) {
            if (message instanceof ToolExecutionResultMessage result
                    && result.id() != null && !result.id().isBlank()) {
                resultsById.put(result.id(), result);
            }
        }
        for (ChatMessage message : messages) {
            if (!(message instanceof AiMessage aiMessage) || !aiMessage.hasToolExecutionRequests()) {
                continue;
            }
            restored.restoreRound(
                    aiMessage.toolExecutionRequests(), resultsById, toolManager);
        }
        restored.finishRestore(successfulWorkspaceMutations);
        states.put(taskId, restored);
    }

    private ChatRequest forceWorkspaceAction(ChatRequest request, Decision decision) {
        boolean hasMutation = decision.successfulWorkspaceMutations() > 0;
        List<ToolSpecification> tools = request.toolSpecifications().stream()
                .filter(specification -> allowedDuringForcedAction(specification, hasMutation))
                .toList();
        ChatRequest.Builder builder = request.toBuilder()
                .messages(withDirective(request.messages(), forceActionDirective(decision, hasMutation)));
        if (!tools.isEmpty()) {
            builder.toolSpecifications(tools);
            if (!hasMutation) {
                builder.toolChoice(ToolChoice.REQUIRED);
            }
        }
        return builder.build();
    }

    private ChatRequest forceFinalResponse(ChatRequest request, Decision decision) {
        return request.toBuilder()
                .messages(withDirective(request.messages(), finalizationDirective(decision)))
                .toolChoice(ToolChoice.NONE)
                .build();
    }

    private boolean allowedDuringForcedAction(ToolSpecification specification,
                                              boolean hasMutation) {
        if (specification == null || specification.name() == null) {
            return false;
        }
        BaseTool tool = toolManager.getTool(specification.name());
        if (tool == null || tool.getRiskLevel() == null) {
            return false;
        }
        if (!hasMutation) {
            return tool.canMutateWorkspace();
        }
        return tool.getRiskLevel() != ToolRiskLevel.READ_ONLY
                || EXIT_TOOL.equals(specification.name());
    }

    private List<ChatMessage> withDirective(List<ChatMessage> messages, String directive) {
        List<ChatMessage> governed = new ArrayList<>(messages);
        for (int index = 0; index < governed.size(); index++) {
            ChatMessage message = governed.get(index);
            if (message instanceof SystemMessage systemMessage) {
                governed.set(index, SystemMessage.from(
                        systemMessage.text() + "\n\n" + directive));
                return List.copyOf(governed);
            }
        }
        governed.addFirst(SystemMessage.from(directive));
        return List.copyOf(governed);
    }

    private String forceActionDirective(Decision decision, boolean hasMutation) {
        String progress = hasMutation
                ? "工作区已有成功修改。"
                : "工作区尚无任何成功修改，不能结束任务。";
        return "运行时生产率约束：连续 " + decision.noMutationTurns()
                + " 个工具回合没有产生工作区修改，累计探索型只读调用 "
                + decision.readOnlyCalls() + " 次。" + progress
                + " 本回合禁止继续读取、搜索或列目录；请立即使用可用写入工具完成最小正确修改"
                + (hasMutation ? "，若任务已完成则调用 exitTool。" : "。");
    }

    private String finalizationDirective(Decision decision) {
        return "运行时生产率约束：已有成功工作区修改，但连续强制执行回合仍未产生新进展。"
                + "停止调用工具，立即输出简短完成说明；后续构建、测试和运行时校验由工程流水线接管。"
                + "当前连续无修改回合数为 " + decision.noMutationTurns() + "。";
    }

    private ChatRequest requireRequest(ChatRequest request) {
        return Objects.requireNonNull(request, "模型请求不能为空");
    }

    private enum Action {
        NONE("none"),
        FORCE("force_action"),
        FINALIZE("finalize");

        private final String metricValue;

        Action(String metricValue) {
            this.metricValue = metricValue;
        }

        private String metricValue() {
            return metricValue;
        }
    }

    private record Decision(Action action,
                            String reason,
                            int readOnlyCalls,
                            int noMutationTurns,
                            int successfulWorkspaceMutations) {

        private static Decision none(int successfulWorkspaceMutations) {
            return new Decision(Action.NONE, "none", 0, 0, successfulWorkspaceMutations);
        }
    }

    private static final class TaskState {
        private boolean initialized;
        private int mutationCheckpoint;
        private int readOnlyCallsWithoutMutation;
        private int modelTurnsWithoutMutation;
        private int forcedActionTurns;
        private int currentTurnCalls;
        private int currentTurnReadOnlyCalls;

        private synchronized void recordTool(ToolRiskLevel riskLevel) {
            currentTurnCalls++;
            if (riskLevel == ToolRiskLevel.READ_ONLY) {
                currentTurnReadOnlyCalls++;
            }
        }

        private synchronized Decision beforeModelTurn(
                int successfulWorkspaceMutations,
                int maxReadOnlyCalls,
                int maxModelTurns,
                int forcedTurnsBeforeFinalize
        ) {
            if (!initialized) {
                initialized = true;
                mutationCheckpoint = successfulWorkspaceMutations;
                return Decision.none(successfulWorkspaceMutations);
            }
            if (successfulWorkspaceMutations > mutationCheckpoint) {
                resetProgressWindow();
            } else if (currentTurnCalls > 0) {
                modelTurnsWithoutMutation++;
                readOnlyCallsWithoutMutation += currentTurnReadOnlyCalls;
            }
            mutationCheckpoint = Math.max(mutationCheckpoint, successfulWorkspaceMutations);
            currentTurnCalls = 0;
            currentTurnReadOnlyCalls = 0;

            boolean readOnlyLimitReached = readOnlyCallsWithoutMutation >= maxReadOnlyCalls;
            boolean turnLimitReached = modelTurnsWithoutMutation >= maxModelTurns;
            if (!readOnlyLimitReached && !turnLimitReached) {
                return Decision.none(successfulWorkspaceMutations);
            }
            forcedActionTurns++;
            String reason = readOnlyLimitReached ? REASON_READ_ONLY : REASON_NO_MUTATION;
            Action action = successfulWorkspaceMutations > 0
                    && forcedActionTurns > forcedTurnsBeforeFinalize
                    ? Action.FINALIZE
                    : Action.FORCE;
            return new Decision(
                    action,
                    reason,
                    readOnlyCallsWithoutMutation,
                    modelTurnsWithoutMutation,
                    successfulWorkspaceMutations
            );
        }

        private synchronized void restoreRound(
                List<ToolExecutionRequest> requests,
                Map<String, ToolExecutionResultMessage> resultsById,
                ToolManager toolManager
        ) {
            int roundCalls = 0;
            int roundReadOnlyCalls = 0;
            boolean completedMutationAttempt = false;
            for (ToolExecutionRequest request : requests) {
                if (request == null || request.name() == null || EXIT_TOOL.equals(request.name())) {
                    continue;
                }
                ToolExecutionResultMessage result = resultsById.get(request.id());
                if (result == null) {
                    continue;
                }
                BaseTool tool = toolManager.getTool(request.name());
                if (tool == null || tool.getRiskLevel() == null) {
                    continue;
                }
                roundCalls++;
                if (tool.getRiskLevel() == ToolRiskLevel.READ_ONLY) {
                    roundReadOnlyCalls++;
                }
                if (tool.canMutateWorkspace()) {
                    completedMutationAttempt = true;
                }
            }
            if (completedMutationAttempt) {
                resetProgressWindow();
            } else if (roundCalls > 0) {
                modelTurnsWithoutMutation++;
                readOnlyCallsWithoutMutation += roundReadOnlyCalls;
            }
        }

        private synchronized void finishRestore(int successfulWorkspaceMutations) {
            initialized = true;
            mutationCheckpoint = successfulWorkspaceMutations;
            currentTurnCalls = 0;
            currentTurnReadOnlyCalls = 0;
        }

        private void resetProgressWindow() {
            readOnlyCallsWithoutMutation = 0;
            modelTurnsWithoutMutation = 0;
            forcedActionTurns = 0;
        }
    }
}
