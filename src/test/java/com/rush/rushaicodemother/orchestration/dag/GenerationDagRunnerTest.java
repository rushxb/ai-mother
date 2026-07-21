package com.rush.rushaicodemother.orchestration.dag;

import cn.hutool.crypto.digest.DigestUtil;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private GenerationOrchestrationRequest newRequest() {
        return new GenerationOrchestrationRequest(
                null,
                "generate",
                CodeGenTypeEnum.VUE_PROJECT,
                "generating",
                false,
                () -> "",
                ignored -> CodeGenTypeEnum.VUE_PROJECT,
                ""
        );
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

    private String fingerprint(List<GenerationAgentNode> nodes) {
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < nodes.size(); index++) {
            GenerationAgentNode node = nodes.get(index);
            canonical.append(index).append('\u0000')
                    .append(node.key()).append('\u0000')
                    .append(node.agentName()).append('\u0000')
                    .append(node.stage()).append('\u0000');
            node.dependencies().stream().sorted().forEach(dependency ->
                    canonical.append(dependency).append('\u0001'));
            canonical.append('\u0002');
        }
        return DigestUtil.sha256Hex(canonical.toString());
    }
}
