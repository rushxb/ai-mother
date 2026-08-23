package com.rush.rushaicodemother.orchestration.runtime.task.queue;

import com.rush.rushaicodemother.config.GenerationTaskQueueProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskDispatcher;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GenerationTaskRedispatchCoordinatorWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GenerationTaskRedispatchCoordinator.class)
            .withBean(DurableGenerationTaskRepository.class,
                    () -> mock(DurableGenerationTaskRepository.class))
            .withBean(GenerationTaskDispatcher.class,
                    () -> mock(GenerationTaskDispatcher.class))
            .withBean(GenerationTaskQueueProperties.class, GenerationTaskQueueProperties::new);

    @Test
    void localTransportMustEnableDurableQueuedTaskRedispatch() {
        contextRunner
                .withPropertyValues("app.generation-task-queue.transport=local")
                .run(context -> assertThat(context)
                        .hasSingleBean(GenerationTaskRedispatchCoordinator.class));
    }
}
