package com.rush.rushaicodemother.orchestration.tool;

import java.util.List;
import java.util.Objects;

/**
 * 一轮工具批次中的一个执行分段。
 *
 * <p>分段之间必须按顺序执行；{@code concurrent} 为 {@code true} 的分段内部可并发。
 * 由 {@link ToolBatchExecutionPlanner} 生成，执行方无需再判断风险等级。</p>
 *
 * @param requests 本分段内的工具请求，携带原始批次序号
 * @param concurrent 段内是否允许并发执行
 */
public record ToolBatchSegment(List<IndexedToolRequest> requests, boolean concurrent) {

    public ToolBatchSegment {
        Objects.requireNonNull(requests, "分段工具请求不能为空");
        if (requests.isEmpty()) {
            throw new IllegalArgumentException("分段至少包含一个工具请求");
        }
        if (concurrent && requests.size() < 2) {
            throw new IllegalArgumentException("并发分段至少包含两个工具请求");
        }
        requests = List.copyOf(requests);
    }

    /** 创建单请求串行分段。 */
    static ToolBatchSegment sequential(IndexedToolRequest request) {
        return new ToolBatchSegment(List.of(request), false);
    }

    /** 创建多请求并发分段。 */
    static ToolBatchSegment concurrent(List<IndexedToolRequest> requests) {
        return new ToolBatchSegment(requests, true);
    }
}
