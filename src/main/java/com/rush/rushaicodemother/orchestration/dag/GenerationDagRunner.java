package com.rush.rushaicodemother.orchestration.dag;

import cn.hutool.crypto.digest.DigestUtil;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 确定性 DAG 调度程序。
 *
 * <p>HHeavy 代已经拥有有界任务执行者的许可。因此这位跑步者
 * 按声明顺序执行就绪节点，而不是创建嵌套执行器
 * 绕过全球资源治理。检查点优化仅限于显式可重放
 * 节点，而副作用节点保留持久的起始边界。</p>
 */
@Component
@Slf4j
public class GenerationDagRunner {

    private final GenerationOrchestrationTaskStore taskStore;
    private final GenerationOrchestrationMetricsCollector metricsCollector;
    private final GenerationExecutionContextService executionContextService;
    private final GenerationTaskSnapshotProperties snapshotProperties;
    private final AgentRuntimeStateMachine stateMachine = new AgentRuntimeStateMachine();

    public GenerationDagRunner(GenerationOrchestrationTaskStore taskStore,
                               GenerationOrchestrationMetricsCollector metricsCollector,
                               GenerationExecutionContextService executionContextService) {
        this(taskStore, metricsCollector, executionContextService, new GenerationTaskSnapshotProperties());
    }

    @Autowired
    public GenerationDagRunner(GenerationOrchestrationTaskStore taskStore,
                               GenerationOrchestrationMetricsCollector metricsCollector,
                               GenerationExecutionContextService executionContextService,
                               GenerationTaskSnapshotProperties snapshotProperties) {
        this.taskStore = taskStore;
        this.metricsCollector = metricsCollector;
        this.executionContextService = executionContextService;
        this.snapshotProperties = snapshotProperties;
    }

    /**
 * 运行生成{@code Dag}处理流程。
 *
 * @param nodes 编排节点列表
 * @param context 执行上下文
 * @return 生成{@code Dag}集合
 */
    public List<GenerationStreamEvent> run(List<GenerationAgentNode> nodes, GenerationAgentContext context) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (context == null || context.getTask() == null) {
            throw new IllegalArgumentException("generation DAG context is required");
        }
        Map<String, GenerationAgentNode> nodeMap = buildNodeMap(nodes);
        List<GenerationStreamEvent> events = new ArrayList<>();
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            bindGraph(nodeMap, context.getTask());
            Set<String> completed = restoredCompletedNodes(nodeMap, context.getTask());
            Set<String> scheduled = new LinkedHashSet<>(completed);
            int deferredCompletionCheckpoints = 0;
            assertCanContinue(context);
            if (context.getTask().getRuntimeState() == AgentRuntimeState.COMPLETED) {
                if (completed.size() != nodeMap.size()) {
                    throw new GenerationDagRecoveryException(
                            GenerationDagRecoveryException.Reason.GRAPH_MISMATCH,
                            "成功终态检查点没有覆盖当前 DAG 的全部节点");
                }
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
                    persistNodeStartCheckpoint(context, node);

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
                    boolean deferred = persistNodeCompletionCheckpoint(
                            context,
                            execution.node(),
                            deferredCompletionCheckpoints + 1
                    );
                    deferredCompletionCheckpoints = deferred
                            ? deferredCompletionCheckpoints + 1
                            : 0;
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
        } catch (GenerationDagRecoveryException recoveryFailure) {
            log.warn(
                    "生成 DAG 检查点恢复被拒绝，appId: {}，taskId: {}，原因: {}",
                    context.getTask().getAppId(),
                    context.getTask().getTaskId(),
                    recoveryFailure.reason()
            );
            throw recoveryFailure;
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

    /** 返回{@code restored}完成{@code Nodes}。 */
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
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (String nodeKey : completed) {
            GenerationAgentNode node = nodeMap.get(nodeKey);
            if (!completed.containsAll(node.dependencies())) {
                throw new IllegalStateException("orchestration checkpoint has incomplete node dependencies");
            }
        }
        return completed;
    }

    /** 执行节点处理流程。 */
    private NodeExecution executeNode(GenerationAgentNode node, GenerationAgentContext context) {
        Instant startedAt = Instant.now();
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
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

    /** 构建并返回节点映射。 */
    private Map<String, GenerationAgentNode> buildNodeMap(List<GenerationAgentNode> nodes) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (nodes == null || nodes.isEmpty()) {
            throw new GenerationDagRecoveryException(
                    GenerationDagRecoveryException.Reason.INVALID_GRAPH,
                    "generation DAG must contain at least one node");
        }
        Map<String, GenerationAgentNode> nodeMap = new LinkedHashMap<>();
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (GenerationAgentNode node : nodes) {
            if (node == null || node.key() == null || node.key().isBlank()
                    || node.agentName() == null || node.agentName().isBlank()
                    || node.stage() == null || node.stage().isBlank()
                    || node.dependencies() == null
                    || node.replayPolicy() == null) {
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

    /** 绑定{@code Graph}。 */
    private void bindGraph(Map<String, GenerationAgentNode> nodeMap,
                           GenerationOrchestrationTask task) {
        String fingerprint = graphFingerprint(nodeMap);
        String legacyFingerprint = legacyGraphFingerprint(nodeMap);
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
            if (existingFingerprint.equals(legacyFingerprint)
                    && canUpgradeLegacyFingerprint(task)) {
                task.setDagFingerprint(fingerprint);
                task.setSchemaVersion(GenerationOrchestrationTask.CURRENT_SCHEMA_VERSION);
                taskStore.save(task);
                return;
            }
            throw new GenerationDagRecoveryException(
                    GenerationDagRecoveryException.Reason.GRAPH_MISMATCH,
                    "the current DAG does not match the persisted checkpoint fingerprint");
        }
    }

    private String graphFingerprint(Map<String, GenerationAgentNode> nodeMap) {
        return graphFingerprint(nodeMap, true);
    }

    private String legacyGraphFingerprint(Map<String, GenerationAgentNode> nodeMap) {
        return graphFingerprint(nodeMap, false);
    }

    /** 返回{@code graph}指纹。 */
    private String graphFingerprint(Map<String, GenerationAgentNode> nodeMap,
                                    boolean includeReplayPolicy) {
        StringBuilder canonical = new StringBuilder();
        int index = 0;
        for (GenerationAgentNode node : nodeMap.values()) {
            canonical.append(index++).append('\u0000')
                    .append(node.key()).append('\u0000')
                    .append(node.agentName()).append('\u0000')
                    .append(node.stage()).append('\u0000');
            if (includeReplayPolicy) {
                canonical.append(node.replayPolicy().name()).append('\u0000');
            }
            node.dependencies().stream().sorted().forEach(dependency ->
                    canonical.append(dependency).append('\u0001'));
            canonical.append('\u0002');
        }
        return DigestUtil.sha256Hex(canonical.toString());
    }

    private boolean canUpgradeLegacyFingerprint(GenerationOrchestrationTask task) {
        if (task.getCurrentNode() != null && !task.getCurrentNode().isBlank()) {
            return false;
        }
        Map<String, String> statuses = task.getNodeStatuses();
        return statuses == null
                || (!statuses.containsValue("running") && !statuses.containsValue("failed"));
    }

    private boolean hasCheckpointProgress(GenerationOrchestrationTask task) {
        return task.getCheckpointVersion() > 0
                || task.getCurrentNode() != null
                || task.getLastCompletedNode() != null
                || (task.getNodeStatuses() != null && !task.getNodeStatuses().isEmpty());
    }

    /** 持久化失败检查点。 */
    private void persistFailureCheckpoint(GenerationOrchestrationTask task, Throwable primaryFailure) {
        try {
            taskStore.save(task);
        } catch (GenerationCheckpointPersistenceException checkpointFailure) {
            primaryFailure.addSuppressed(checkpointFailure);
        }
    }

    /** 持久化节点开始检查点。 */
    private void persistNodeStartCheckpoint(GenerationAgentContext context,
                                            GenerationAgentNode node) {
        Instant startedAt = Instant.now();
        if (snapshotProperties.isReplaySafeStartCheckpointElisionEnabled()
                && node.replayPolicy() == GenerationNodeReplayPolicy.REPLAY_SAFE) {
            metricsCollector.recordNodeStartCheckpoint(
                    context.getOrchestrationMode(), node.key(), "elided",
                    Duration.between(startedAt, Instant.now()));
            return;
        }
        try {
            taskStore.save(context.getTask());
            metricsCollector.recordNodeStartCheckpoint(
                    context.getOrchestrationMode(), node.key(), "persisted",
                    Duration.between(startedAt, Instant.now()));
        } catch (RuntimeException | Error failure) {
            metricsCollector.recordNodeStartCheckpoint(
                    context.getOrchestrationMode(), node.key(), "failed",
                    Duration.between(startedAt, Instant.now()));
            throw failure;
        }
    }

    /** 持久化节点完成检查点。 */
    private boolean persistNodeCompletionCheckpoint(GenerationAgentContext context,
                                                    GenerationAgentNode node,
                                                    int pendingCompletionCount) {
        Instant startedAt = Instant.now();
        if (canCoalesceCompletionCheckpoint(node, pendingCompletionCount)) {
            metricsCollector.recordNodeCompletionCheckpoint(
                    context.getOrchestrationMode(), node.key(), "coalesced",
                    Duration.between(startedAt, Instant.now()));
            return true;
        }
        try {
            taskStore.save(context.getTask());
            metricsCollector.recordNodeCompletionCheckpoint(
                    context.getOrchestrationMode(), node.key(), "persisted",
                    Duration.between(startedAt, Instant.now()));
            return false;
        } catch (RuntimeException | Error failure) {
            metricsCollector.recordNodeCompletionCheckpoint(
                    context.getOrchestrationMode(), node.key(), "failed",
                    Duration.between(startedAt, Instant.now()));
            throw failure;
        }
    }

    private boolean canCoalesceCompletionCheckpoint(GenerationAgentNode node,
                                                    int pendingCompletionCount) {
        return snapshotProperties.isReplaySafeStartCheckpointElisionEnabled()
                && snapshotProperties.isReplaySafeCompletionCheckpointCoalescingEnabled()
                && node.replayPolicy() == GenerationNodeReplayPolicy.REPLAY_SAFE
                && pendingCompletionCount < snapshotProperties.getReplaySafeCompletionCheckpointInterval();
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

    /** 构建并返回事件。 */
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
