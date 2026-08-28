package com.rush.rushaicodemother.infrastructure.persistence.benchmark;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkExecutionFacts;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DurableGenerationBenchmarkExecutionFactsProviderTest {

    @Test
    void providerMustProjectFrozenTargetTypeAndFallbackFromDurableCommand() {
        DurableGenerationTaskRepository repository = mock(DurableGenerationTaskRepository.class);
        GenerationTaskCommand command = mock(GenerationTaskCommand.class);
        when(command.taskId()).thenReturn("task-upgrade");
        when(command.appId()).thenReturn(101L);
        when(command.codeGenType()).thenReturn(CodeGenTypeEnum.FULL_STACK_PROJECT);
        when(command.fallbackReason()).thenReturn("capability_negotiated_from_create");
        when(repository.findCommandByTaskId("task-upgrade")).thenReturn(Optional.of(command));

        DurableGenerationBenchmarkExecutionFactsProvider provider =
                new DurableGenerationBenchmarkExecutionFactsProvider(repository);

        GenerationBenchmarkExecutionFacts facts = provider.findByTaskId("task-upgrade")
                .orElseThrow();
        assertEquals(new GenerationBenchmarkExecutionFacts(
                "task-upgrade",
                101L,
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                "capability_negotiated_from_create"
        ), facts);
        assertTrue(facts.fallbackObserved());
    }
}
