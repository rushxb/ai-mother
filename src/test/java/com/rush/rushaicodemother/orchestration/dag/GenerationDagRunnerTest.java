package com.rush.rushaicodemother.orchestration.dag;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class GenerationDagRunnerTest {

    @Test
    void shouldPersistOnlySanitizedFailureDetails() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationDagRunner runner = new GenerationDagRunner(
                taskStore,
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry())
        );
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-secret");
        task.setAppId(1L);
        task.setStatus("running");
        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                null,
                "generate",
                CodeGenTypeEnum.VUE_PROJECT,
                "generating",
                false,
                () -> "",
                ignored -> CodeGenTypeEnum.VUE_PROJECT,
                ""
        );
        GenerationAgentContext context = new GenerationAgentContext(request, task, true);
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
    }
}
