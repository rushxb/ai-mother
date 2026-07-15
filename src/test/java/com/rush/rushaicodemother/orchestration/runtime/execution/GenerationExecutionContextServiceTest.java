package com.rush.rushaicodemother.orchestration.runtime.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationExecutionContextServiceTest {

    @Test
    void sameApplicationRejectsConcurrentTasksAndAllowsRestartAfterFinish() {
        GenerationRuntimeProperties properties = properties();
        GenerationExecutionContextTest.MutableClock clock =
                new GenerationExecutionContextTest.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GenerationExecutionContextService service = new GenerationExecutionContextService(properties, clock);

        GenerationExecutionContext first = service.start("task-1", 11L, 22L);
        assertThrows(GenerationExecutionPolicyException.class,
                () -> service.start("task-2", 11L, 22L));

        service.finish("task-1", "success");
        GenerationExecutionContext second = service.start("task-2", 11L, 22L);

        assertNotSame(first, second);
        assertTrue(first.isCompleted());
        assertEquals("task-2", service.getByAppId(11L).orElseThrow().taskId());
    }

    @Test
    void cancellationDeadlineClampingAndFinishAreExposedThroughNarrowFacade() {
        GenerationRuntimeProperties properties = properties();
        GenerationExecutionContextTest.MutableClock clock =
                new GenerationExecutionContextTest.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GenerationExecutionContextService service = new GenerationExecutionContextService(properties, clock);
        service.start("task-1", 11L, 22L);

        clock.advance(Duration.ofMinutes(19));
        assertEquals(Duration.ofMinutes(1), service.clampTimeout("task-1", Duration.ofMinutes(5)));

        service.cancelByAppId(11L, "user_requested");
        assertTrue(service.shouldStop("task-1"));
        assertThrows(GenerationExecutionCancelledException.class,
                () -> service.assertCanContinue("task-1"));

        service.finishByAppId(11L, "cancelled");
        assertTrue(service.getByTaskId("task-1").isEmpty());
        assertTrue(service.getByAppId(11L).isEmpty());
    }

    private GenerationRuntimeProperties properties() {
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();
        properties.setTaskTimeout(Duration.ofMinutes(20));
        properties.setModelCallTimeout(Duration.ofMinutes(8));
        properties.setMinimumOperationTimeout(Duration.ofMillis(500));
        return properties;
    }
}
