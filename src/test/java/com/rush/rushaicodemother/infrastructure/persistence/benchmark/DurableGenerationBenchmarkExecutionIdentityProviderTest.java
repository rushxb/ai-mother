package com.rush.rushaicodemother.infrastructure.persistence.benchmark;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkExecutionIdentity;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DurableGenerationBenchmarkExecutionIdentityProviderTest {

    @Test
    void providerMustProjectFrozenTargetTypeFromDurableCommand() {
        DurableGenerationTaskRepository repository = mock(DurableGenerationTaskRepository.class);
        GenerationTaskCommand command = mock(GenerationTaskCommand.class);
        when(command.taskId()).thenReturn("task-upgrade");
        when(command.appId()).thenReturn(101L);
        when(command.codeGenType()).thenReturn(CodeGenTypeEnum.FULL_STACK_PROJECT);
        when(repository.findCommandByTaskId("task-upgrade")).thenReturn(Optional.of(command));

        DurableGenerationBenchmarkExecutionIdentityProvider provider =
                new DurableGenerationBenchmarkExecutionIdentityProvider(repository);

        assertEquals(
                new GenerationBenchmarkExecutionIdentity(
                        "task-upgrade", 101L, CodeGenTypeEnum.FULL_STACK_PROJECT),
                provider.findByTaskId("task-upgrade").orElseThrow()
        );
    }
}
