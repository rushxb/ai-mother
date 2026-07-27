package com.rush.rushaicodemother.ai.tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * 最大风险为{@link ToolRiskLevel#DESTRUCTIVE}的工具合约。
 *
 * <p>中央调用策略在LangChain4j调用工具方法之前调用此钩子。
 * 实现必须确认所请求的子操作是非破坏性的，或者
 * 在任何破坏性副作用发生之前提高现有的持久批准信号。</p>
 */
public interface ApprovalGatedTool {

    void authorizeInvocation(ToolExecutionRequest request, Long appId);
}
