package com.rush.rushaicodemother.ai.tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * Contract for tools whose maximum risk is {@link ToolRiskLevel#DESTRUCTIVE}.
 *
 * <p>The central invocation policy calls this hook before LangChain4j invokes the tool method.
 * Implementations must either confirm that the requested sub-operation is non-destructive or
 * raise the existing durable approval signal before any destructive side effect can occur.</p>
 */
public interface ApprovalGatedTool {

    void authorizeInvocation(ToolExecutionRequest request, Long appId);
}
