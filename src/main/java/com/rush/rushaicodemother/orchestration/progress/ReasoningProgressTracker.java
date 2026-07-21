package com.rush.rushaicodemother.orchestration.progress;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Converts private model-reasoning signals into bounded, user-safe progress events.
 *
 * <p>The tracker deliberately never accepts reasoning text. This keeps hidden chain-of-thought
 * out of SSE, replay streams, logs and chat history while still making long model calls visible
 * to users.</p>
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
