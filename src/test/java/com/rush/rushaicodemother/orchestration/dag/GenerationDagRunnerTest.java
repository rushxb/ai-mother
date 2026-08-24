package com.rush.rushaicodemother.orchestration.dag;

import cn.hutool.crypto.digest.DigestUtil;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.rush.rushaicodemother.orchestration.GenerationOrchestrationTestFixture.frozenRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;

class GenerationDagRunnerTest {

    @Test
    void independentNodesMustRunDeterministicallyWithoutNestedConcurrency() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                mock(GenerationExecutionContextService.class)
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-sequential");
        task.setAppId(1L);
        task.setStatus("running");
        GenerationAgentContext context = new GenerationAgentContext(newRequest(), task, true);
        AtomicInteger activeNodes = new AtomicInteger();
        AtomicInteger maximumActiveNodes = new AtomicInteger();
        List<String> executionOrder = new CopyOnWriteArrayList<>();

        runner.run(List.of(
                successfulNode("first", activeNodes, maximumActiveNodes, executionOrder),
                successfulNode("second", activeNodes, maximumActiveNodes, executionOrder)
        ), context);

        assertEquals(List.of("first", "second"), executionOrder);
        assertEquals(1, maximumActiveNodes.get());
        assertEquals(AgentRuntimeState.COMPLETED, task.getRuntimeState());
        assertEquals("second", task.getLastCompletedNode());
        assertEquals(3, task.getCheckpointVersion());
    }

    @Test
    void shouldPersistOnlySanitizedFailureDetails() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                mock(GenerationExecutionContextService.class)
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-secret");
        task.setAppId(1L);
        task.setStatus("running");
        GenerationAgentContext context = new GenerationAgentContext(newRequest(), task, true);
        GenerationAgentNode failingNode = new GenerationAgentNode() {
            @Override
            public String key() {
                return "failing-node";
            }

            @Override
            public String agentName() {
                return "FailingAgent";
            }

            @Override
            public String stage() {
                return "generation";
            }

            @Override
            public List<String> dependencies() {
                return List.of();
            }

            @Override
            public AgentNodeResult execute(GenerationAgentContext ignored) {
                throw new IllegalStateException("provider-api-key=secret-value");
            }
        };

        assertThrows(CompletionException.class, () -> runner.run(List.of(failingNode), context));

        assertEquals("failed", task.getStatus());
        assertEquals("代码生成失败，请稍后重试。", task.getFailureMessage());
        GenerationStreamEvent failedEvent = task.getEvents().stream()
                .filter(event -> "failed".equals(event.getData().get("status")))
                .findFirst()
                .orElseThrow();
        assertEquals("代码生成失败，请稍后重试。", failedEvent.getData().get("summary"));
        assertEquals("runtime", failedEvent.getData().get("category"));
        assertFalse(task.toString().contains("secret-value"));
        assertEquals(AgentRuntimeState.FAILED, task.getRuntimeState());
        assertEquals("runtime", task.getTerminationReason());
    }

    @Test
    void nodeMustNotReachDoneWithoutItsDeclaredCheckpointArtifact() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                mock(GenerationExecutionContextService.class)
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-missing-node-artifact");
        task.setAppId(1L);
        task.setStatus("running");
        GenerationAgentNode node = artifactNode(
                "producer",
                Set.of("required_artifact"),
                AgentNodeResult.of("错误地报告完成", List.of(), Map.of())
        );

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> runner.run(
                        List.of(node),
                        new GenerationAgentContext(newRequest(), task, true)
                )
        );

        assertTrue(failure.getCause().getMessage().contains("required_artifact"));
        assertEquals("failed", task.getNodeStatuses().get("producer"));
        assertFalse(task.getArtifacts().containsKey("required_artifact"));
        assertEquals(AgentRuntimeState.FAILED, task.getRuntimeState());
    }

    @Test
    void resumeMustSkipCompletedNodesAndHydrateTheirArtifacts() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                mock(GenerationExecutionContextService.class)
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-resume");
        task.setAppId(1L);
        task.setStatus("running");
        task.setRuntimeState(AgentRuntimeState.RUNNING);
        task.setCheckpointVersion(1);
        task.setLastCompletedNode("first");
        task.getNodeStatuses().put("first", "done");
        task.getTimings().put("first", 12L);
        task.getArtifacts().put("requirements", GenerationArtifact.of(
                "requirements", "Planner", "requirements",
                Map.of("targetType", "backend_project", "upgradeRequired", true)
        ));
        AtomicInteger firstExecutions = new AtomicInteger();
        AtomicInteger secondExecutions = new AtomicInteger();
        List<GenerationAgentNode> nodes = List.of(
                countingNode("first", List.of(), firstExecutions),
                countingNode("second", List.of("first"), secondExecutions)
        );
        task.setDagFingerprint(fingerprint(nodes));
        GenerationAgentContext context = new GenerationAgentContext(newRequest(), task, true);

        runner.run(nodes, context);

        assertEquals(0, firstExecutions.get());
        assertEquals(1, secondExecutions.get());
        assertEquals(CodeGenTypeEnum.BACKEND_PROJECT, context.getTargetType());
        assertEquals(12L, context.getTimings().get("first"));
        assertEquals(AgentRuntimeState.COMPLETED, task.getRuntimeState());
        assertEquals(3, task.getCheckpointVersion());
    }

    @Test
    void deadlineReachedInsideNodeMustFailAtCheckpointBoundary() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationExecutionContextService executionContextService = mock(GenerationExecutionContextService.class);
        AtomicBoolean deadlineReached = new AtomicBoolean();
        doAnswer(invocation -> {
            if (deadlineReached.get()) {
                throw new GenerationDeadlineExceededException("task-deadline");
            }
            return null;
        }).when(executionContextService).assertCanContinue("task-deadline");
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                executionContextService
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-deadline");
        task.setAppId(1L);
        task.setStatus("running");
        GenerationAgentContext context = new GenerationAgentContext(newRequest(), task, true);
        GenerationAgentNode deadlineNode = new GenerationAgentNode() {
            @Override
            public String key() {
                return "slow";
            }

            @Override
            public String agentName() {
                return "slow";
            }

            @Override
            public String stage() {
                return "test";
            }

            @Override
            public List<String> dependencies() {
                return List.of();
            }

            @Override
            public AgentNodeResult execute(GenerationAgentContext ignored) {
                deadlineReached.set(true);
                return AgentNodeResult.of("late", List.of(), Map.of());
            }
        };

        assertThrows(CompletionException.class, () -> runner.run(List.of(deadlineNode), context));

        assertEquals("failed", task.getStatus());
        assertEquals("failed", task.getNodeStatuses().get("slow"));
        assertEquals(AgentRuntimeState.FAILED, task.getRuntimeState());
    }

    @Test
    void runningCheckpointMustFailClosedWithoutRepeatingTheNode() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                mock(GenerationExecutionContextService.class)
        );
        AtomicInteger executions = new AtomicInteger();
        List<GenerationAgentNode> nodes = List.of(countingNode("write", List.of(), executions));
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-ambiguous");
        task.setAppId(1L);
        task.setStatus("running");
        task.setRuntimeState(AgentRuntimeState.RUNNING);
        task.setCurrentNode("write");
        task.getNodeStatuses().put("write", "running");
        task.setDagFingerprint(fingerprint(nodes));

        GenerationDagRecoveryException failure = assertThrows(
                GenerationDagRecoveryException.class,
                () -> runner.run(nodes, new GenerationAgentContext(newRequest(), task, true)));

        assertEquals(GenerationDagRecoveryException.Reason.AMBIGUOUS_NODE, failure.reason());
        assertEquals(0, executions.get());
    }

    @Test
    void checkpointFailureBeforeNodeExecutionMustPreventTheSideEffect() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        AtomicInteger saves = new AtomicInteger();
        doAnswer(invocation -> {
            if (saves.incrementAndGet() == 2) {
                throw new GenerationCheckpointPersistenceException(
                        GenerationCheckpointPersistenceException.Reason.STORAGE_FAILURE,
                        "database unavailable");
            }
            return null;
        }).when(taskStore).save(org.mockito.ArgumentMatchers.any());
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                mock(GenerationExecutionContextService.class)
        );
        AtomicInteger executions = new AtomicInteger();
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-checkpoint-failure");
        task.setAppId(1L);
        task.setStatus("running");

        GenerationCheckpointPersistenceException failure = assertThrows(
                GenerationCheckpointPersistenceException.class,
                () -> runner.run(
                        List.of(countingNode("write", List.of(), executions)),
                        new GenerationAgentContext(newRequest(), task, true)));

        assertEquals(GenerationCheckpointPersistenceException.Reason.STORAGE_FAILURE,
                failure.reason());
        assertEquals(0, executions.get());
    }

    @Test
    void nodeCompletionCheckpointFailureMustStopBeforeDependentSideEffects() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        AtomicInteger saves = new AtomicInteger();
        doAnswer(invocation -> {
            if (saves.incrementAndGet() == 3) {
                throw new GenerationCheckpointPersistenceException(
                        GenerationCheckpointPersistenceException.Reason.STORAGE_FAILURE,
                        "database unavailable after node side effect");
            }
            return null;
        }).when(taskStore).save(org.mockito.ArgumentMatchers.any());
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                mock(GenerationExecutionContextService.class)
        );
        AtomicInteger firstExecutions = new AtomicInteger();
        AtomicInteger dependentExecutions = new AtomicInteger();
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-completion-checkpoint-failure");
        task.setAppId(1L);
        task.setStatus("running");

        assertThrows(GenerationCheckpointPersistenceException.class, () -> runner.run(
                List.of(
                        countingNode("write", List.of(), firstExecutions),
                        countingNode("publish", List.of("write"), dependentExecutions)
                ),
                new GenerationAgentContext(newRequest(), task, true)));

        assertEquals(1, firstExecutions.get());
        assertEquals(0, dependentExecutions.get());
    }

    @Test
    void changedDagMustBeRejectedBeforeExecutingAnyNode() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                mock(GenerationExecutionContextService.class)
        );
        AtomicInteger executions = new AtomicInteger();
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-graph-mismatch");
        task.setAppId(1L);
        task.setStatus("running");
        task.setDagFingerprint("0".repeat(64));

        GenerationDagRecoveryException failure = assertThrows(
                GenerationDagRecoveryException.class,
                () -> runner.run(
                        List.of(countingNode("current", List.of(), executions)),
                        new GenerationAgentContext(newRequest(), task, true)));

        assertEquals(GenerationDagRecoveryException.Reason.GRAPH_MISMATCH, failure.reason());
        assertEquals(0, executions.get());
    }

    @Test
    void completedCheckpointMissingCurrentDagNodeMustFailClosed() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                mock(GenerationExecutionContextService.class)
        );
        AtomicInteger firstExecutions = new AtomicInteger();
        AtomicInteger secondExecutions = new AtomicInteger();
        List<GenerationAgentNode> nodes = List.of(
                countingNode("first", List.of(), firstExecutions),
                countingNode("second", List.of("first"), secondExecutions)
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-incomplete-completed");
        task.setAppId(1L);
        task.setStatus("completed");
        task.setRuntimeState(AgentRuntimeState.COMPLETED);
        task.setDagFingerprint(fingerprint(nodes));
        task.setLastCompletedNode("first");
        task.setCheckpointVersion(2L);
        task.setTerminationReason("success");
        task.getNodeStatuses().put("first", "done");

        GenerationDagRecoveryException failure = assertThrows(
                GenerationDagRecoveryException.class,
                () -> runner.run(nodes, new GenerationAgentContext(newRequest(), task, true)));

        assertEquals(GenerationDagRecoveryException.Reason.GRAPH_MISMATCH, failure.reason());
        assertEquals(0, firstExecutions.get());
        assertEquals(0, secondExecutions.get());
        assertEquals("completed", task.getStatus());
        assertEquals(AgentRuntimeState.COMPLETED, task.getRuntimeState());
    }

    @Test
    void restoredArtifactMapKeyMustMatchThePersistedArtifactIdentity() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                mock(GenerationExecutionContextService.class)
        );
        GenerationAgentNode node = artifactNode(
                "producer",
                Set.of("required_artifact"),
                AgentNodeResult.of("done", List.of(), Map.of())
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-mismatched-artifact-identity");
        task.setAppId(1L);
        task.setStatus("completed");
        task.setRuntimeState(AgentRuntimeState.COMPLETED);
        task.setDagFingerprint(fingerprint(List.of(node)));
        task.getNodeStatuses().put("producer", "done");
        task.getArtifacts().put(
                "required_artifact",
                GenerationArtifact.of(
                        "foreign_artifact",
                        "Producer",
                        "错误制品",
                        Map.of("value", "foreign")
                )
        );

        GenerationDagRecoveryException failure = assertThrows(
                GenerationDagRecoveryException.class,
                () -> runner.run(
                        List.of(node),
                        new GenerationAgentContext(newRequest(), task, true)
                )
        );

        assertEquals(GenerationDagRecoveryException.Reason.ARTIFACT_MISMATCH, failure.reason());
    }

    @Test
    void replaySafeStartCheckpointElisionMustRemainDisabledByDefault() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        List<String> persistedCurrentNodes = new ArrayList<>();
        doAnswer(invocation -> {
            GenerationOrchestrationTask saved = invocation.getArgument(0);
            persistedCurrentNodes.add(saved.getCurrentNode() == null ? "" : saved.getCurrentNode());
            return null;
        }).when(taskStore).save(org.mockito.ArgumentMatchers.any());
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                mock(GenerationExecutionContextService.class)
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-replay-safe-default");
        task.setAppId(1L);
        task.setStatus("running");

        runner.run(
                List.of(replaySafeNode("safe", new AtomicInteger(), new AtomicBoolean(false))),
                new GenerationAgentContext(newRequest(), task, true)
        );

        assertEquals(List.of("", "safe", "", ""), persistedCurrentNodes);
    }

    @Test
    void enabledElisionMustReplaySafeNodeFromLastDurableBoundaryAfterProcessLoss() {
        GenerationTaskSnapshotProperties properties = new GenerationTaskSnapshotProperties();
        properties.setReplaySafeStartCheckpointElisionEnabled(true);
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        AtomicReference<String> persistedSnapshot = new AtomicReference<>();
        doAnswer(invocation -> {
            GenerationOrchestrationTask saved = invocation.getArgument(0);
            persistedSnapshot.set(cn.hutool.json.JSONUtil.toJsonStr(saved));
            return null;
        }).when(taskStore).save(org.mockito.ArgumentMatchers.any());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(meterRegistry),
                mock(GenerationExecutionContextService.class),
                properties
        );
        AtomicInteger executions = new AtomicInteger();
        AtomicBoolean loseProcessOnFirstExecution = new AtomicBoolean(true);
        List<GenerationAgentNode> nodes = List.of(
                replaySafeNode("safe", executions, loseProcessOnFirstExecution));
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-replay-safe-crash");
        task.setAppId(1L);
        task.setStatus("running");

        assertThrows(SimulatedProcessLoss.class, () -> runner.run(
                nodes, new GenerationAgentContext(newRequest(), task, true)));

        GenerationOrchestrationTask persisted = cn.hutool.json.JSONUtil.toBean(
                persistedSnapshot.get(), GenerationOrchestrationTask.class);
        assertNull(persisted.getCurrentNode());
        assertFalse(persisted.getNodeStatuses().containsKey("safe"));

        runner.run(nodes, new GenerationAgentContext(newRequest(), persisted, true));

        assertEquals(2, executions.get());
        assertEquals(AgentRuntimeState.COMPLETED, persisted.getRuntimeState());
        assertEquals(2, meterRegistry.find("generation_orchestration_node_start_checkpoints_total")
                .tag("dag_node", "safe")
                .tag("outcome", "elided")
                .counter()
                .count(), 0.001);
    }

    @Test
    void enabledCompletionCoalescingMustPersistAtTheConfiguredInterval() {
        GenerationTaskSnapshotProperties properties = new GenerationTaskSnapshotProperties();
        properties.setReplaySafeStartCheckpointElisionEnabled(true);
        properties.setReplaySafeCompletionCheckpointCoalescingEnabled(true);
        properties.setReplaySafeCompletionCheckpointInterval(3);
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        List<String> durableLastCompletedNodes = new ArrayList<>();
        doAnswer(invocation -> {
            GenerationOrchestrationTask saved = invocation.getArgument(0);
            durableLastCompletedNodes.add(saved.getLastCompletedNode() == null
                    ? ""
                    : saved.getLastCompletedNode());
            return null;
        }).when(taskStore).save(org.mockito.ArgumentMatchers.any());
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(meterRegistry),
                mock(GenerationExecutionContextService.class),
                properties
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-completion-coalescing-interval");
        task.setAppId(1L);
        task.setStatus("running");

        runner.run(
                List.of(
                        replaySafeNode("first", new AtomicInteger(), new AtomicBoolean(false)),
                        replaySafeNode("second", new AtomicInteger(), new AtomicBoolean(false)),
                        replaySafeNode("third", new AtomicInteger(), new AtomicBoolean(false)),
                        replaySafeNode("fourth", new AtomicInteger(), new AtomicBoolean(false))
                ),
                new GenerationAgentContext(newRequest(), task, true)
        );

        assertEquals(List.of("", "third", "fourth"), durableLastCompletedNodes);
        assertEquals(1, meterRegistry.find("generation_orchestration_node_completion_checkpoints_total")
                .tag("dag_node", "first")
                .tag("outcome", "coalesced")
                .counter()
                .count(), 0.001);
        assertEquals(1, meterRegistry.find("generation_orchestration_node_completion_checkpoints_total")
                .tag("dag_node", "third")
                .tag("outcome", "persisted")
                .counter()
                .count(), 0.001);
    }

    @Test
    void processLossAfterCoalescedCompletionMustReplayFromLastDurableBoundary() {
        GenerationTaskSnapshotProperties properties = new GenerationTaskSnapshotProperties();
        properties.setReplaySafeStartCheckpointElisionEnabled(true);
        properties.setReplaySafeCompletionCheckpointCoalescingEnabled(true);
        properties.setReplaySafeCompletionCheckpointInterval(4);
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        AtomicReference<String> persistedSnapshot = new AtomicReference<>();
        doAnswer(invocation -> {
            GenerationOrchestrationTask saved = invocation.getArgument(0);
            persistedSnapshot.set(cn.hutool.json.JSONUtil.toJsonStr(saved));
            return null;
        }).when(taskStore).save(org.mockito.ArgumentMatchers.any());
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                mock(GenerationExecutionContextService.class),
                properties
        );
        AtomicInteger firstExecutions = new AtomicInteger();
        AtomicInteger secondExecutions = new AtomicInteger();
        List<GenerationAgentNode> nodes = List.of(
                replaySafeNode("first", firstExecutions, new AtomicBoolean(false)),
                replaySafeNode("second", secondExecutions, new AtomicBoolean(true))
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-completion-coalescing-crash");
        task.setAppId(1L);
        task.setStatus("running");

        assertThrows(SimulatedProcessLoss.class, () -> runner.run(
                nodes, new GenerationAgentContext(newRequest(), task, true)));

        GenerationOrchestrationTask persisted = cn.hutool.json.JSONUtil.toBean(
                persistedSnapshot.get(), GenerationOrchestrationTask.class);
        assertFalse(persisted.getNodeStatuses().containsKey("first"));
        assertFalse(persisted.getNodeStatuses().containsKey("second"));

        runner.run(nodes, new GenerationAgentContext(newRequest(), persisted, true));

        assertEquals(2, firstExecutions.get());
        assertEquals(2, secondExecutions.get());
        assertEquals(AgentRuntimeState.COMPLETED, persisted.getRuntimeState());
    }

    @Test
    void nonReplaySafeNodeStartMustFlushEarlierCoalescedCompletions() {
        GenerationTaskSnapshotProperties properties = new GenerationTaskSnapshotProperties();
        properties.setReplaySafeStartCheckpointElisionEnabled(true);
        properties.setReplaySafeCompletionCheckpointCoalescingEnabled(true);
        properties.setReplaySafeCompletionCheckpointInterval(4);
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        List<String> durableSnapshots = new ArrayList<>();
        doAnswer(invocation -> {
            GenerationOrchestrationTask saved = invocation.getArgument(0);
            durableSnapshots.add(cn.hutool.json.JSONUtil.toJsonStr(saved));
            return null;
        }).when(taskStore).save(org.mockito.ArgumentMatchers.any());
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                mock(GenerationExecutionContextService.class),
                properties
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-completion-coalescing-boundary");
        task.setAppId(1L);
        task.setStatus("running");

        runner.run(
                List.of(
                        replaySafeNode("safe", new AtomicInteger(), new AtomicBoolean(false)),
                        countingNode("unsafe", List.of("safe"), new AtomicInteger())
                ),
                new GenerationAgentContext(newRequest(), task, true)
        );

        assertTrue(durableSnapshots.stream()
                .map(snapshot -> cn.hutool.json.JSONUtil.toBean(
                        snapshot, GenerationOrchestrationTask.class))
                .anyMatch(snapshot -> "done".equals(snapshot.getNodeStatuses().get("safe"))
                        && "running".equals(snapshot.getNodeStatuses().get("unsafe"))));
    }

    @Test
    void ordinaryFailureMustPersistEarlierCoalescedNodeState() {
        assertTerminalFailurePersistsEarlierCoalescedNodeState(
                new IllegalStateException("expected failure"),
                "runtime"
        );
    }

    @Test
    void cancellationMustPersistEarlierCoalescedNodeState() {
        assertTerminalFailurePersistsEarlierCoalescedNodeState(
                new GenerationExecutionCancelledException("user_requested"),
                "model_cancelled"
        );
    }

    private void assertTerminalFailurePersistsEarlierCoalescedNodeState(RuntimeException terminalFailure,
                                                                         String expectedCategory) {
        GenerationTaskSnapshotProperties properties = new GenerationTaskSnapshotProperties();
        properties.setReplaySafeStartCheckpointElisionEnabled(true);
        properties.setReplaySafeCompletionCheckpointCoalescingEnabled(true);
        properties.setReplaySafeCompletionCheckpointInterval(4);
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        List<String> durableSnapshots = new ArrayList<>();
        doAnswer(invocation -> {
            GenerationOrchestrationTask saved = invocation.getArgument(0);
            durableSnapshots.add(cn.hutool.json.JSONUtil.toJsonStr(saved));
            return null;
        }).when(taskStore).save(org.mockito.ArgumentMatchers.any());
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                mock(GenerationExecutionContextService.class),
                properties
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-coalesced-terminal-" + expectedCategory);
        task.setAppId(1L);
        task.setStatus("running");
        GenerationArtifact firstArtifact = GenerationArtifact.of(
                "first_artifact",
                "First",
                "first",
                Map.of("value", "durable")
        );

        assertThrows(CompletionException.class, () -> runner.run(
                List.of(
                        replaySafeResultNode(
                                "first",
                                List.of(),
                                AgentNodeResult.of("first done", List.of(firstArtifact), Map.of())
                        ),
                        replaySafeFailingNode("second", List.of("first"), terminalFailure)
                ),
                new GenerationAgentContext(newRequest(), task, true)
        ));

        GenerationOrchestrationTask persisted = cn.hutool.json.JSONUtil.toBean(
                durableSnapshots.getLast(), GenerationOrchestrationTask.class);
        assertEquals("failed", persisted.getStatus());
        assertEquals(AgentRuntimeState.FAILED, persisted.getRuntimeState());
        assertEquals(expectedCategory, persisted.getTerminationReason());
        assertEquals("done", persisted.getNodeStatuses().get("first"));
        assertEquals("failed", persisted.getNodeStatuses().get("second"));
        assertTrue(persisted.getArtifacts().containsKey("first_artifact"));
        assertTrue(persisted.getEvents().stream().anyMatch(event ->
                "first".equals(event.getData().get("dagNode"))
                        && "done".equals(event.getData().get("status"))));
        assertTrue(persisted.getEvents().stream().anyMatch(event ->
                "second".equals(event.getData().get("dagNode"))
                        && "failed".equals(event.getData().get("status"))
                        && expectedCategory.equals(event.getData().get("category"))));
    }

    private GenerationOrchestrationRequest newRequest() {
        return frozenRequest(null, "generate", CodeGenTypeEnum.VUE_PROJECT, "generating", false);
    }

    private GenerationAgentNode artifactNode(String key,
                                             Set<String> requiredArtifactKeys,
                                             AgentNodeResult result) {
        return new GenerationAgentNode() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public String agentName() {
                return key;
            }

            @Override
            public String stage() {
                return "planning";
            }

            @Override
            public List<String> dependencies() {
                return List.of();
            }

            @Override
            public Set<String> requiredArtifactKeys() {
                return requiredArtifactKeys;
            }

            @Override
            public AgentNodeResult execute(GenerationAgentContext ignored) {
                return result;
            }
        };
    }

    private GenerationAgentNode successfulNode(String key,
                                               AtomicInteger activeNodes,
                                               AtomicInteger maximumActiveNodes,
                                               List<String> executionOrder) {
        return new GenerationAgentNode() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public String agentName() {
                return key;
            }

            @Override
            public String stage() {
                return "test";
            }

            @Override
            public List<String> dependencies() {
                return List.of();
            }

            @Override
            public AgentNodeResult execute(GenerationAgentContext ignored) {
                int active = activeNodes.incrementAndGet();
                maximumActiveNodes.accumulateAndGet(active, Math::max);
                executionOrder.add(key);
                activeNodes.decrementAndGet();
                return AgentNodeResult.of("done", List.of(), java.util.Map.of());
            }
        };
    }

    private GenerationAgentNode countingNode(String key,
                                             List<String> dependencies,
                                             AtomicInteger executions) {
        return new GenerationAgentNode() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public String agentName() {
                return key;
            }

            @Override
            public String stage() {
                return "test";
            }

            @Override
            public List<String> dependencies() {
                return dependencies;
            }

            @Override
            public AgentNodeResult execute(GenerationAgentContext ignored) {
                executions.incrementAndGet();
                return AgentNodeResult.of("done", List.of(), Map.of());
            }
        };
    }

    private GenerationAgentNode replaySafeNode(String key,
                                               AtomicInteger executions,
                                               AtomicBoolean loseProcessOnFirstExecution) {
        return new GenerationAgentNode() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public String agentName() {
                return key;
            }

            @Override
            public String stage() {
                return "test";
            }

            @Override
            public List<String> dependencies() {
                return List.of();
            }

            @Override
            public GenerationNodeReplayPolicy replayPolicy() {
                return GenerationNodeReplayPolicy.REPLAY_SAFE;
            }

            @Override
            public AgentNodeResult execute(GenerationAgentContext ignored) {
                executions.incrementAndGet();
                if (loseProcessOnFirstExecution.compareAndSet(true, false)) {
                    throw new SimulatedProcessLoss();
                }
                return AgentNodeResult.of("done", List.of(), Map.of());
            }
        };
    }

    private GenerationAgentNode replaySafeResultNode(String key,
                                                      List<String> dependencies,
                                                      AgentNodeResult result) {
        return replaySafeExecutingNode(key, dependencies, ignored -> result);
    }

    private GenerationAgentNode replaySafeFailingNode(String key,
                                                       List<String> dependencies,
                                                       RuntimeException failure) {
        return replaySafeExecutingNode(key, dependencies, ignored -> {
            throw failure;
        });
    }

    private GenerationAgentNode replaySafeExecutingNode(
            String key,
            List<String> dependencies,
            java.util.function.Function<GenerationAgentContext, AgentNodeResult> execution) {
        return new GenerationAgentNode() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public String agentName() {
                return key;
            }

            @Override
            public String stage() {
                return "test";
            }

            @Override
            public List<String> dependencies() {
                return dependencies;
            }

            @Override
            public GenerationNodeReplayPolicy replayPolicy() {
                return GenerationNodeReplayPolicy.REPLAY_SAFE;
            }

            @Override
            public AgentNodeResult execute(GenerationAgentContext context) {
                return execution.apply(context);
            }
        };
    }

    private String fingerprint(List<GenerationAgentNode> nodes) {
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < nodes.size(); index++) {
            GenerationAgentNode node = nodes.get(index);
            canonical.append(index).append('\u0000')
                    .append(node.key()).append('\u0000')
                    .append(node.agentName()).append('\u0000')
                    .append(node.stage()).append('\u0000')
                    .append(node.replayPolicy().name()).append('\u0000');
            node.dependencies().stream().sorted().forEach(dependency ->
                    canonical.append(dependency).append('\u0001'));
            canonical.append('\u0002');
        }
        return DigestUtil.sha256Hex(canonical.toString());
    }

    private static final class SimulatedProcessLoss extends Error {
    }
}
