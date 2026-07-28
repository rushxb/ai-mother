package com.rush.rushaicodemother.orchestration.runtime.agent;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 统一治理主生成与人工审批恢复使用的智能体模型和工具回合。 */
@Component
public class GenerationAgentTurnPolicy {

    private static final String FINALIZATION_DIRECTIVE =
            "运行时约束：工具回合预算已经用完。本回合禁止继续调用工具，"
                    + "请根据已有修改立即给出简短完成说明；构建、测试和发布由工程流水线继续执行。";

    private final GenerationExecutionContextService executionContextService;

    @Autowired
    public GenerationAgentTurnPolicy(GenerationExecutionContextService executionContextService) {
        this.executionContextService = Objects.requireNonNull(
                executionContextService, "生成执行上下文服务不能为空");
    }

    /** 仅供不经过 Spring 的兼容测试构造器使用。 */
    GenerationAgentTurnPolicy() {
        this.executionContextService = null;
    }

    /** 每个根模型尝试拥有独立账本，只有根重试或新修复轮次会重置。 */
    public void beginAttempt(Long appId,
                             CodeGenTypeEnum codeGenType,
                             GenerationPerformanceProfile profile) {
        if (executionContextService == null || appId == null) {
            return;
        }
        int toolRoundLimit = maximumToolRounds(codeGenType, profile);
        executionContextService.getByAppId(appId)
                .ifPresent(context -> beginAttempt(context, codeGenType, profile));
    }

    public void beginAttempt(GenerationExecutionContext executionContext,
                             CodeGenTypeEnum codeGenType,
                             GenerationPerformanceProfile profile) {
        Objects.requireNonNull(executionContext, "生成执行上下文不能为空")
                .beginAgentAttempt(maximumToolRounds(codeGenType, profile));
    }

    /** 从审批检查点恢复同一次根尝试，不能创建新的局部预算。 */
    public void restoreAttempt(GenerationExecutionContext executionContext,
                               CodeGenTypeEnum codeGenType,
                               GenerationPerformanceProfile profile,
                               List<ChatMessage> restoredMessages) {
        Objects.requireNonNull(executionContext, "生成执行上下文不能为空");
        int observedToolRounds = observedToolRounds(restoredMessages);
        if (observedToolRounds <= 0) {
            throw new IllegalStateException("审批恢复会话缺少待处理工具回合");
        }
        executionContext.restoreAgentAttempt(
                maximumToolRounds(codeGenType, profile), observedToolRounds);
    }

    /**
 * 准备后续流程所需的模型轮次。
 *
 * @param appId 应用编号
 * @param codeGenType 代码生成类型
 * @param profile 配置档
 * @param request 请求参数
 * @return 模型轮次
 */
    public PreparedModelTurn prepareModelTurn(Long appId,
                                              CodeGenTypeEnum codeGenType,
                                              GenerationPerformanceProfile profile,
                                              ChatRequest request) {
        ChatRequest requiredRequest = Objects.requireNonNull(request, "模型请求不能为空");
        int toolRoundLimit = maximumToolRounds(codeGenType, profile);
        if (executionContextService == null || appId == null) {
            return PreparedModelTurn.unmanaged(requiredRequest, toolRoundLimit);
        }
        return executionContextService.getByAppId(appId)
                .map(context -> prepareModelTurn(
                        context, codeGenType, profile, requiredRequest))
                .orElseGet(() -> PreparedModelTurn.unmanaged(
                        requiredRequest, toolRoundLimit));
    }

    /**
 * 准备后续流程所需的模型轮次。
 *
 * @param executionContext 执行上下文
 * @param codeGenType 代码生成类型
 * @param profile 配置档
 * @param request 请求参数
 * @return 模型轮次
 */
    public PreparedModelTurn prepareModelTurn(GenerationExecutionContext executionContext,
                                              CodeGenTypeEnum codeGenType,
                                              GenerationPerformanceProfile profile,
                                              ChatRequest request) {
        Objects.requireNonNull(executionContext, "生成执行上下文不能为空");
        ChatRequest requiredRequest = Objects.requireNonNull(request, "模型请求不能为空");
        int toolRoundLimit = maximumToolRounds(codeGenType, profile);
        int modelTurn = executionContext.reserveAgentModelTurn(toolRoundLimit);
        boolean toolCallsAllowed = modelTurn <= toolRoundLimit;
        ChatRequest governed = toolCallsAllowed
                ? requiredRequest
                : forceFinalization(requiredRequest);
        return new PreparedModelTurn(
                governed, modelTurn, toolRoundLimit, toolCallsAllowed, true);
    }

    /**
 * 断言工具执行{@code Allowed}仍满足当前执行约束。
 *
 * @param appId 应用编号
 */
    public void assertToolExecutionAllowed(Long appId) {
        if (executionContextService == null || appId == null) {
            return;
        }
        executionContextService.getByAppId(appId)
                .ifPresent(GenerationExecutionContext::assertAgentToolExecutionAllowed);
    }

    /**
 * 断言工具执行{@code Allowed}仍满足当前执行约束。
 *
 * @param executionContext 执行上下文
 */
    public void assertToolExecutionAllowed(GenerationExecutionContext executionContext) {
        Objects.requireNonNull(executionContext, "生成执行上下文不能为空")
                .assertAgentToolExecutionAllowed();
    }

    /**
 * 返回{@code maximum}工具{@code Rounds}。
 *
 * @param codeGenType 代码生成类型
 * @param profile 配置档
 * @return 计算或处理后的数值结果
 */
    public int maximumToolRounds(CodeGenTypeEnum codeGenType,
                                 GenerationPerformanceProfile profile) {
        if (codeGenType == null) {
            throw new IllegalArgumentException("代码生成类型不能为空");
        }
        int configured = profile == null
                ? switch (codeGenType) {
                    case FULL_STACK_PROJECT -> 32;
                    case BACKEND_PROJECT -> 20;
                    default -> 10;
                }
                : profile.maxToolInvocations();
        if (configured <= 0 || configured == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("智能体工具回合上限无效");
        }
        return configured;
    }

    /** 模型工具协议还需要一个禁用工具后的最终响应槽位。 */
    public int maximumModelResponses(CodeGenTypeEnum codeGenType,
                                     GenerationPerformanceProfile profile) {
        return Math.addExact(maximumToolRounds(codeGenType, profile), 1);
    }

    /** 重建模型请求并禁用全部工具，强制模型在最后一个响应槽位直接收尾。 */
    private ChatRequest forceFinalization(ChatRequest request) {
        ChatRequestParameters parameters = ChatRequestParameters.builder()
                .modelName(request.modelName())
                .temperature(request.temperature())
                .topP(request.topP())
                .topK(request.topK())
                .frequencyPenalty(request.frequencyPenalty())
                .presencePenalty(request.presencePenalty())
                .maxOutputTokens(request.maxOutputTokens())
                .stopSequences(request.stopSequences())
                .toolSpecifications(List.of())
                .toolChoice(ToolChoice.NONE)
                .responseFormat(request.responseFormat())
                .build();
        return ChatRequest.builder()
                .messages(withFinalizationDirective(request.messages()))
                .parameters(parameters)
                .build();
    }

    /** 创建包含收尾指令的新消息列表。 */
    private List<ChatMessage> withFinalizationDirective(List<ChatMessage> messages) {
        List<ChatMessage> governed = new ArrayList<>(messages == null ? List.of() : messages);
        for (int index = 0; index < governed.size(); index++) {
            ChatMessage message = governed.get(index);
            if (message instanceof SystemMessage systemMessage) {
                governed.set(index, SystemMessage.from(
                        systemMessage.text() + "\n\n" + FINALIZATION_DIRECTIVE));
                return List.copyOf(governed);
            }
        }
        governed.addFirst(SystemMessage.from(FINALIZATION_DIRECTIVE));
        return List.copyOf(governed);
    }

    /** 观测并记录{@code d}工具{@code Rounds}。 */
    private int observedToolRounds(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int rounds = 0;
        for (ChatMessage message : messages) {
            if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                rounds = Math.addExact(rounds, 1);
            }
        }
        return rounds;
    }

    public record PreparedModelTurn(
            ChatRequest request,
            int modelTurn,
            int toolRoundLimit,
            boolean toolCallsAllowed,
            boolean managed
    ) {
        /** 创建{@code Prepared}模型轮次实例并完成必要的依赖和初始状态设置。 */
        public PreparedModelTurn {
            Objects.requireNonNull(request, "模型请求不能为空");
            if (modelTurn < 0 || toolRoundLimit <= 0) {
                throw new IllegalArgumentException("智能体模型回合决策无效");
            }
        }

        private static PreparedModelTurn unmanaged(ChatRequest request,
                                                   int toolRoundLimit) {
            return new PreparedModelTurn(
                    request, 0, toolRoundLimit, true, false);
        }
    }
}
