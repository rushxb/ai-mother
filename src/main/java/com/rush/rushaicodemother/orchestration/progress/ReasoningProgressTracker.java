package com.rush.rushaicodemother.orchestration.progress;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将私有模型推理信号转换为有界的、用户安全的进度事件。
 *
 * <p>跟踪器故意不接受推理文本。这保持了隐藏的思路
 * 在 SSE 之外，重放流、日志和聊天历史记录，同时仍然使长模型调用可见
 * 致用户.</p>
 */
public final class ReasoningProgressTracker {

    private static final String AGENT = "AI";
    private static final String STAGE = "reasoning";
    private static final String DAG_NODE = "model_reasoning";

    private final String taskId;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();

    public ReasoningProgressTracker(String taskId) {
        this.taskId = normalize(taskId);
    }

    public Optional<GenerationStreamEvent> startIfNeeded() {
        if (!started.compareAndSet(false, true)) {
            return Optional.empty();
        }
        return Optional.of(event("running", "正在分析需求并制定执行策略"));
    }

    public Optional<GenerationStreamEvent> completeIfStarted() {
        if (!started.get() || !terminal.compareAndSet(false, true)) {
            return Optional.empty();
        }
        return Optional.of(event("done", "分析完成，正在生成可见结果"));
    }

    public Optional<GenerationStreamEvent> failIfStarted() {
        if (!started.get() || !terminal.compareAndSet(false, true)) {
            return Optional.empty();
        }
        return Optional.of(event("failed", "分析阶段未完成，正在执行恢复策略"));
    }

    private GenerationStreamEvent event(String status, String summary) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", AGENT);
        data.put("stage", STAGE);
        data.put("status", status);
        data.put("summary", summary);
        data.put("dagNode", DAG_NODE);
        data.put("taskId", taskId);
        data.put("recoverable", true);
        data.put("visibility", "summary");
        return GenerationStreamEvent.agentEvent("", Map.copyOf(data));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
