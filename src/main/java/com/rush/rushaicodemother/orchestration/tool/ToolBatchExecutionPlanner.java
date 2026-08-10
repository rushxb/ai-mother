package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.ai.tools.BaseTool;
import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.ai.tools.ToolRiskLevel;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 把模型单轮返回的工具批次切分为可并发执行的分段。
 *
 * <p>模型常在一轮内请求多个只读工具（读文件、搜索、健康检查）。串行执行时耗时是各次 IO 之和，
 * 而只读工具之间不存在写冲突，可以安全并发。本模块只负责「怎么分段」这一个决策，
 * 不涉及线程与执行，便于单独推理与测试。</p>
 *
 * <p>分段规则：连续的只读请求聚成一个并发段；任何非只读请求（写、破坏性、外部副作用）
 * 单独成段并保持原有相对顺序。因此工作区变更的先后语义与串行执行完全一致，
 * 「先读后写」「写后再读」这类依赖不会被打乱。风险未知的工具按非只读处理，保守优先。</p>
 */
@Component
@RequiredArgsConstructor
public class ToolBatchExecutionPlanner {

    private final ToolManager toolManager;

    /**
     * 将一轮工具请求切分为按序执行的分段。
     *
     * @param requests 模型本轮返回的工具请求，保持原始顺序
     * @return 有序分段列表；调用方需按列表顺序执行，段内可并发
     */
    public List<ToolBatchSegment> plan(List<ToolExecutionRequest> requests) {
        Objects.requireNonNull(requests, "工具请求列表不能为空");
        List<ToolBatchSegment> segments = new ArrayList<>();
        List<IndexedToolRequest> pendingReadOnly = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) {
            ToolExecutionRequest request = requests.get(index);
            IndexedToolRequest indexed = new IndexedToolRequest(index, request);
            if (isReadOnly(request)) {
                pendingReadOnly.add(indexed);
                continue;
            }
            // 遇到有副作用的请求：先收口已累积的只读段，保证写操作看到全部前序读取结果。
            flushReadOnly(pendingReadOnly, segments);
            segments.add(ToolBatchSegment.sequential(indexed));
        }
        flushReadOnly(pendingReadOnly, segments);
        return List.copyOf(segments);
    }

    /** 将累积的只读请求收口为一个分段：单个请求无需并发开销，多个才走并发。 */
    private void flushReadOnly(List<IndexedToolRequest> pendingReadOnly,
                               List<ToolBatchSegment> segments) {
        if (pendingReadOnly.isEmpty()) {
            return;
        }
        if (pendingReadOnly.size() == 1) {
            segments.add(ToolBatchSegment.sequential(pendingReadOnly.getFirst()));
        } else {
            segments.add(ToolBatchSegment.concurrent(List.copyOf(pendingReadOnly)));
        }
        pendingReadOnly.clear();
    }

    /**
     * 判断请求是否为只读工具。
     *
     * <p>工具未注册或风险等级缺失时返回 {@code false}：宁可串行执行，也不并发未知副作用。</p>
     */
    private boolean isReadOnly(ToolExecutionRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return false;
        }
        BaseTool tool = toolManager.getTool(request.name());
        return tool != null && tool.getRiskLevel() == ToolRiskLevel.READ_ONLY;
    }
}
