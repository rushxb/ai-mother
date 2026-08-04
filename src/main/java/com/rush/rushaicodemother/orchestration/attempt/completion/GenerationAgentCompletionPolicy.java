package com.rush.rushaicodemother.orchestration.attempt.completion;

import com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentTurnPolicy;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Agent 模型停止调用工具时的完成判定，防止把空回答误当成代码生成完成。 */
@Component
public class GenerationAgentCompletionPolicy {

    private static final String CORRECTION_MESSAGE =
            "完成门禁提示：当前尚未检测到任何成功的工作区变更。"
                    + "如果任务需要修改代码，请继续使用写工具完成落盘；"
                    + "不要只给说明文字，也不要声明任务已完成。";

    public AgentCompletionDecision evaluate(
            GenerationExecutionContext executionContext,
            GenerationAgentTurnPolicy.PreparedModelTurn preparedTurn
    ) {
        Objects.requireNonNull(executionContext, "生成执行上下文不能为空");
        Objects.requireNonNull(preparedTurn, "智能体模型回合不能为空");
        if (executionContext.successfulWorkspaceMutationCount() > 0) {
            return new AgentCompletionDecision(true, false, "已检测到有效工作区变更");
        }
        if (preparedTurn.toolCallsAllowed()) {
            return new AgentCompletionDecision(false, true, CORRECTION_MESSAGE);
        }
        return new AgentCompletionDecision(false, false, "智能体未产生有效工作区变更，不能结束代码生成阶段");
    }

    public record AgentCompletionDecision(boolean completable,
                                          boolean retryable,
                                          String message) {
        public AgentCompletionDecision {
            message = message == null ? "" : message.trim();
        }

        public GenerationCompletionEvidenceException toException() {
            return new GenerationCompletionEvidenceException(new GenerationCompletionDecision(
                    false,
                    List.of(GenerationCompletionRequirement.WORKSPACE_RESULT),
                    message));
        }
    }
}
