package com.rush.rushaicodemother.orchestration.dag;

import cn.hutool.crypto.digest.DigestUtil;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

/**
 * Deterministic DAG scheduler.
 *
 * <p>Heavy generation already owns a permit from the bounded task executor. This runner therefore
 * executes ready nodes in declaration order instead of creating a nested executor that would
 * bypass global resource governance. A node completion is persisted before the next node starts,
 * which also makes the diagnostic snapshot a safe checkpoint boundary.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GenerationDagRunner {

    private final GenerationOrchestrationTaskStore taskStore;
    private final GenerationOrchestrationMetricsCollector metricsCollector;
    private final GenerationExecutionContextService executionContextService;
    private final AgentRuntimeStateMachine stateMachine = new AgentRuntimeStateMachine();

    public List<GenerationStreamEvent> run(List<GenerationAgentNode> nodes, GenerationAgentContext context) {
        if (context == null || context.getTask() == null) {
            throw new IllegalArgumentException("generation DAG context is required");
        }
        Map<String, GenerationAgentNode> nodeMap = buildNodeMap(nodes);
        List<GenerationStreamEvent> events = new ArrayList<>();
        try {
            bindGraph(nodeMap, context.getTask());
            Set<String> completed = restoredCompletedNodes(nodeMap, context.getTask());
            Set<String> scheduled = new LinkedHashSet<>(completed);
            assertCanContinue(context);
            if (completed.size() == nodeMap.size()
                    && context.getTask().getRuntimeState() == AgentRuntimeState.COMPLETED) {
                return events;
            }
            while (completed.size() < nodeMap.size()) {
                assertCanContinue(context);
                List<GenerationAgentNode> readyNodes = nodeMap.values().stream()
                        .filter(node -> !scheduled.contains(node.key()))
                        .filter(node -> completed.containsAll(node.dependencies()))
                        .toList();
                if (readyNodes.isEmpty()) {
                    throw new IllegalStateException("generation DAG has a cycle or a missing dependency");
                }
                for (GenerationAgentNode node : readyNodes) {
                    scheduled.add(node.key());
                    stateMachine.startNode(context.getTask(), node);
                    GenerationStreamEvent runningEvent = buildEvent(
                            context, node, "running", "node execution started", null, 0L);
                    events.add(runningEvent);
                    context.getTask().getEvents().add(runningEvent);
                    context.getTask().getNodeStatuses().put(node.key(), "running");
                    taskStore.save(context.getTask());

                    NodeExecution execution = executeNode(node, context);
                    completed.add(execution.node().key());
                    context.putArtifacts(execution.result().artifacts());
                    context.recordTiming(execution.node().key(), execution.durationMs());
                    context.getTask().getTimings().put(execution.node().key(), execution.durationMs());
                    metricsCollector.recordNodeDuration(
                            context.getOrchestrationMode(),
                            execution.node().key(),
                            execution.node().stage(),
                            "done",
                            Duration.ofMillis(execution.durationMs())
                    );
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
                    stateMachine.checkpointNode(context.getTask(), execution.node());
                    taskStore.save(context.getTask());
                }
            }
            context.getTask().setStatus("completed");
            stateMachine.complete(context.getTask());
            taskStore.save(context.getTask());
            return events;
        } catch (GenerationCheckpointPersistenceException checkpointFailure) {
            log.error(
                    "Generation DAG stopped because its checkpoint was not durable, appId: {}, taskId: {}, reason: {}",
                    context.getTask().getAppId(),
                    context.getTask().getTaskId(),
                    checkpointFailure.reason(),
                    LogExceptionSanitizer.sanitize(checkpointFailure)
            );
            throw checkpointFailure;
        } catch (Exception exception) {
            Throwable failure = unwrapCompletionFailure(exception);
            GenerationErrorClassifier.GenerationError publicError = GenerationErrorClassifier.classify(failure);
            context.getTask().setStatus("failed");
            context.getTask().setFailureMessage(publicError.message());
            stateMachine.fail(context.getTask(), publicError.category());
            persistFailureCheckpoint(context.getTask(), failure);
            log.error(
                    "Generation DAG execution failed, appId: {}, taskId: {}",
                    context.getTask().getAppId(),
                    context.getTask().getTaskId(),
                    LogExceptionSanitizer.sanitize(failure)
            );
            throw exception;
        }
    }

    private Set<String> restoredCompletedNodes(Map<String, GenerationAgentNode> nodeMap,
                                               GenerationOrchestrationTask task) {
        Set<String> completed = new LinkedHashSet<>();
        if (task.getNodeStatuses() == null) {
            return completed;
        }
        task.getNodeStatuses().forEach((nodeKey, status) -> {
            if (!nodeMap.containsKey(nodeKey)) {
                throw new GenerationDagRecoveryException(
                        GenerationDagRecoveryException.Reason.GRAPH_MISMATCH,
                        "orchestration checkpoint references a node outside the bound DAG");
            }
            if ("done".equals(status)) {
                completed.add(nodeKey);
                return;
            }
            if ("running".equals(status)) {
                throw new GenerationDagRecoveryException(
                        GenerationDagRecoveryException.Reason.AMBIGUOUS_NODE,
                        "a previously running DAG node cannot be retried without an idempotency contract");
            }
            if ("failed".equals(status)) {
                throw new GenerationDagRecoveryException(
                        GenerationDagRecoveryException.Reason.AMBIGUOUS_NODE,
                        "a failed DAG node requires an explicit recovery decision");
            }
        });
        for (String nodeKey : completed) {
            GenerationAgentNode node = nodeMap.get(nodeKey);
            if (!completed.containsAll(node.dependencies())) {
                throw new IllegalStateException("orchestration checkpoint has incomplete node dependencies");
            }
        }
        return completed;
    }

    private NodeExecution executeNode(GenerationAgentNode node, GenerationAgentContext context) {
        Instant startedAt = Instant.now();
        try {
            assertCanContinue(context);
            AgentNodeResult result = node.execute(context);
            assertCanContinue(context);
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            return new NodeExecution(node, result, durationMs);
        } catch (Exception exception) {
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            GenerationErrorClassifier.GenerationError publicError = GenerationErrorClassifier.classify(exception);
            GenerationStreamEvent failedEvent = buildEvent(
                    context,
                    node,
                    "failed",
                    publicError.message(),
                    Map.of(
                            "category", publicError.category(),
                            "recoverable", publicError.recoverable()
                    ),
                    durationMs
            );
            context.getTask().getEvents().add(failedEvent);
            context.getTask().getNodeStatuses().put(node.key(), "failed");
            metricsCollector.recordNodeDuration(
                    context.getOrchestrationMode(),
                    node.key(),
                    node.stage(),
                    "failed",
                    Duration.ofMillis(durationMs)
            );
            try {
                taskStore.save(context.getTask());
            } catch (GenerationCheckpointPersistenceException checkpointFailure) {
                exception.addSuppressed(checkpointFailure);
            }
            throw new CompletionException(exception);
        }
    }

    private Map<String, GenerationAgentNode> buildNodeMap(List<GenerationAgentNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new GenerationDagRecoveryException(
                    GenerationDagRecoveryException.Reason.INVALID_GRAPH,
                    "generation DAG must contain at least one node");
        }
        Map<String, GenerationAgentNode> nodeMap = new LinkedHashMap<>();
        for (GenerationAgentNode node : nodes) {
            if (node == null || node.key() == null || node.key().isBlank()
                    || node.agentName() == null || node.agentName().isBlank()
                    || node.stage() == null || node.stage().isBlank()
                    || node.dependencies() == null) {
                throw new GenerationDagRecoveryException(
                        GenerationDagRecoveryException.Reason.INVALID_GRAPH,
                        "generation DAG contains an invalid node declaration");
            }
            if (nodeMap.putIfAbsent(node.key(), node) != null) {
                throw new GenerationDagRecoveryException(
                        GenerationDagRecoveryException.Reason.INVALID_GRAPH,
                        "generation DAG contains duplicate node keys");
            }
        }
        for (GenerationAgentNode node : nodeMap.values()) {
            for (String dependency : node.dependencies()) {
                if (dependency == null || dependency.isBlank() || !nodeMap.containsKey(dependency)) {
                    throw new GenerationDagRecoveryException(
                            GenerationDagRecoveryException.Reason.INVALID_GRAPH,
                            "generation DAG contains an unknown dependency");
                }
                if (node.key().equals(dependency)) {
                    throw new GenerationDagRecoveryException(
                            GenerationDagRecoveryException.Reason.INVALID_GRAPH,
                            "generation DAG node cannot depend on itself");
                }
            }
        }
        return nodeMap;
    }

    private void bindGraph(Map<String, GenerationAgentNode> nodeMap,
                           GenerationOrchestrationTask task) {
        String fingerprint = graphFingerprint(nodeMap);
        String existingFingerprint = task.getDagFingerprint();
        if (existingFingerprint == null || existingFingerprint.isBlank()) {
            if (hasCheckpointProgress(task)) {
                throw new GenerationDagRecoveryException(
                        GenerationDagRecoveryException.Reason.LEGACY_CHECKPOINT,
                        "an in-progress legacy checkpoint has no DAG fingerprint");
            }
            task.setDagFingerprint(fingerprint);
            task.setSchemaVersion(GenerationOrchestrationTask.CURRENT_SCHEMA_VERSION);
            taskStore.save(task);
            return;
        }
        if (!existingFingerprint.equals(fingerprint)) {
            throw new GenerationDagRecoveryException(
                    GenerationDagRecoveryException.Reason.GRAPH_MISMATCH,
                    "the current DAG does not match the persisted checkpoint fingerprint");
        }
    }

    private String graphFingerprint(Map<String, GenerationAgentNode> nodeMap) {
        StringBuilder canonical = new StringBuilder();
        int index = 0;
        for (GenerationAgentNode node : nodeMap.values()) {
            canonical.append(index++).append('\u0000')
                    .append(node.key()).append('\u0000')
                    .append(node.agentName()).append('\u0000')
                    .append(node.stage()).append('\u0000');
            node.dependencies().stream().sorted().forEach(dependency ->
                    canonical.append(dependency).append('\u0001'));
            canonical.append('\u0002');
        }
        return DigestUtil.sha256Hex(canonical.toString());
    }

    private boolean hasCheckpointProgress(GenerationOrchestrationTask task) {
        return task.getCheckpointVersion() > 0
                || task.getCurrentNode() != null
                || task.getLastCompletedNode() != null
                || (task.getNodeStatuses() != null && !task.getNodeStatuses().isEmpty());
    }

    private void persistFailureCheckpoint(GenerationOrchestrationTask task, Throwable primaryFailure) {
        try {
            taskStore.save(task);
        } catch (GenerationCheckpointPersistenceException checkpointFailure) {
            primaryFailure.addSuppressed(checkpointFailure);
        }
    }

    private Throwable unwrapCompletionFailure(Exception exception) {
        if (exception instanceof CompletionException && exception.getCause() != null) {
            return exception.getCause();
        }
        return exception;
    }

    private void assertCanContinue(GenerationAgentContext context) {
        executionContextService.assertCanContinue(context.getTask().getTaskId());
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
        data.put("runtimeState", context.getTask().getRuntimeState().name().toLowerCase());
        data.put("checkpointVersion", context.getTask().getCheckpointVersion());
        data.put("recoverable", true);
        if (extraData != null) {
            data.putAll(extraData);
        }
        return GenerationStreamEvent.agentEvent("", data);
    }

    private record NodeExecution(GenerationAgentNode node, AgentNodeResult result, long durationMs) {
    }
}
