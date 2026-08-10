package com.rush.rushaicodemother.orchestration.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.Objects;

/**
 * 携带原始批次序号的工具请求。
 *
 * <p>并发执行会打乱完成顺序，而对话历史与用户事件流必须保持模型请求的原始顺序，
 * 否则重放与审批恢复会读到与首次执行不同的消息序列。序号让结果可以还原回原位。</p>
 *
 * @param index 在模型本轮返回的工具请求列表中的下标
 * @param request 工具请求本体
 */
public record IndexedToolRequest(int index, ToolExecutionRequest request) {

    public IndexedToolRequest {
        if (index < 0) {
            throw new IllegalArgumentException("工具请求批次序号不能为负");
        }
        Objects.requireNonNull(request, "工具请求不能为空");
    }
}
