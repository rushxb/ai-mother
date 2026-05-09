package com.yupi.yuaicodemother.orchestration.dag;

import com.yupi.yuaicodemother.core.handler.GenerationStreamEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 轻量 DAG 调度器：按依赖分层，并行执行同层节点。
 */
@Component
@RequiredArgsConstructor
public class GenerationDagRunner {

    private final GenerationOrchestrationTaskStore taskStore;

    public List<GenerationStreamEvent> run(List<GenerationAgentNode> nodes, GenerationAgentContext context) {
        Map<String, GenerationAgentNode> nodeMap = new LinkedHashMap<>();
        nodes.forEach(node -> nodeMap.put(node.key(), node));
        List<GenerationStreamEvent> events = new ArrayList<>();
        Set<String> completed = new LinkedHashSet<>();
        Set<String> scheduled = new LinkedHashSet<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (completed.size() < nodeMap.size()) {
                List<GenerationAgentNode> readyNodes = nodeMap.values().stream()
                        .filter(node -> !scheduled.contains(node.key()))
                        .filter(node -> completed.containsAll(node.dependencies()))
                        .toList();
                if (readyNodes.isEmpty()) {
                    throw new IllegalStateException("DAG 编排存在循环依赖或缺失依赖");
                }
                List<CompletableFuture<NodeExecution>> futures = readyNodes.stream()
                        .map(node -> {
                            scheduled.add(node.key());
                            GenerationStreamEvent runningEvent = buildEvent(context, node, "running", "开始执行", null, 0L);
                            synchronized (events) {
                                events.add(runningEvent);
                            }
                            context.getTask().getEvents().add(runningEvent);
                            context.getTask().getNodeStatuses().put(node.key(), "running");
                            taskStore.save(context.getTask());
                            return CompletableFuture.supplyAsync(() -> executeNode(node, context), executor);
                        })
                        .toList();
                for (CompletableFuture<NodeExecution> future : futures) {
                    NodeExecution execution = future.join();
                    completed.add(execution.node().key());
                    context.putArtifacts(execution.result().artifacts());
                    context.recordTiming(execution.node().key(), execution.durationMs());
                    context.getTask().getTimings().put(execution.node().key(), execution.durationMs());
                    execution.result().artifacts().forEach(artifact ->
                            context.getTask().getArtifacts().put(artifact.key(), artifact));
                    GenerationStreamEvent doneEvent = buildEvent(
                            context,
                            execution.node(),
                            "done",
                            execution.result().summary(),
                            execution.result().data(),
                            execution.durationMs()
                    );
                    events.add(doneEvent);
                    context.getTask().getEvents().add(doneEvent);
                    context.getTask().getNodeStatuses().put(execution.node().key(), "done");
                    taskStore.save(context.getTask());
                }
            }
            context.getTask().setStatus("completed");
            taskStore.save(context.getTask());
            return events;
        } catch (Exception e) {
            context.getTask().setStatus("failed");
            context.getTask().setFailureMessage(e.getMessage());
            taskStore.save(context.getTask());
            throw e;
        }
    }

    private NodeExecution executeNode(GenerationAgentNode node, GenerationAgentContext context) {
        Instant startedAt = Instant.now();
        try {
            AgentNodeResult result = node.execute(context);
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            return new NodeExecution(node, result, durationMs);
        } catch (Exception e) {
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            GenerationStreamEvent failedEvent = buildEvent(context, node, "failed", e.getMessage(), null, durationMs);
            context.getTask().getEvents().add(failedEvent);
            context.getTask().getNodeStatuses().put(node.key(), "failed");
            taskStore.save(context.getTask());
            throw e;
        }
    }

    private GenerationStreamEvent buildEvent(GenerationAgentContext context,
                                             GenerationAgentNode node,
                                             String status,
                                             String summary,
                                             Map<String, Object> extraData,
                                             long durationMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", node.agentName());
        data.put("stage", node.stage());
        data.put("status", status);
        data.put("summary", summary);
        data.put("dagNode", node.key());
        data.put("taskId", context.getTask().getTaskId());
        data.put("durationMs", durationMs);
        data.put("recoverable", true);
        if (extraData != null) {
            data.putAll(extraData);
        }
        return GenerationStreamEvent.agentEvent("", data);
    }

    private record NodeExecution(GenerationAgentNode node, AgentNodeResult result, long durationMs) {
    }
}
