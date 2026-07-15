package com.rush.rushaicodemother.orchestration.runtime.execution;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationExecutionContextTest {

    @Test
    void concurrentToolWriteBudgetConsumptionNeverExceedsConfiguredLimit() throws Exception {
        assertConcurrentBudgetLimit(GenerationBudgetKind.TOOL_WRITE);
    }

    @Test
    void concurrentRepairRoundBudgetConsumptionNeverExceedsConfiguredLimit() throws Exception {
        assertConcurrentBudgetLimit(GenerationBudgetKind.REPAIR_ROUND);
    }

    @Test
    void clampTimeoutUsesRemainingDeadlineAndRejectsUnsafeMinimumWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        GenerationExecutionContext context = context(3, Duration.ofSeconds(10), clock);

        clock.advance(Duration.ofSeconds(7));
        assertEquals(Duration.ofSeconds(3), context.clampTimeout(Duration.ofSeconds(30)));

        clock.advance(Duration.ofMillis(2_600));
        assertThrows(GenerationDeadlineExceededException.class,
                () -> context.clampTimeout(Duration.ofSeconds(1)));
    }

    @Test
    void cancellationAndCompletionRejectFurtherWork() {
        GenerationExecutionContext cancelled = context(3, Duration.ofMinutes(1), new MutableClock(Instant.EPOCH));
        cancelled.cancel("user_requested");
        assertTrue(cancelled.isCancelled());
        assertEquals("user_requested", cancelled.cancellationReason());
        assertThrows(GenerationExecutionCancelledException.class, cancelled::assertCanContinue);

        GenerationExecutionContext completed = context(3, Duration.ofMinutes(1), new MutableClock(Instant.EPOCH));
        completed.complete("success");
        assertTrue(completed.isCompleted());
        assertFalse(completed.hasRemainingBudget(GenerationBudgetKind.MODEL_ATTEMPT));
        assertThrows(GenerationExecutionPolicyException.class,
                () -> completed.consume(GenerationBudgetKind.MODEL_ATTEMPT));
    }

    @Test
    void snapshotContainsStableIdentityDeadlineAndBudgetState() {
        MutableClock clock = new MutableClock(Instant.parse("2026-02-02T00:00:00Z"));
        GenerationExecutionContext context = context(4, Duration.ofMinutes(2), clock);
        context.consume(GenerationBudgetKind.REPAIR_ROUND);

        GenerationExecutionSnapshot snapshot = context.snapshot();

        assertEquals("task-1", snapshot.taskId());
        assertEquals(11L, snapshot.appId());
        assertEquals(22L, snapshot.userId());
        assertEquals(clock.instant().plus(Duration.ofMinutes(2)), snapshot.deadlineAt());
        assertEquals(1, snapshot.usages().get(GenerationBudgetKind.REPAIR_ROUND));
        assertEquals(4, snapshot.limits().get(GenerationBudgetKind.REPAIR_ROUND));
    }

    private void assertConcurrentBudgetLimit(GenerationBudgetKind budgetKind) throws Exception {
        GenerationExecutionContext context = context(10, Duration.ofMinutes(5), new MutableClock(Instant.EPOCH));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Boolean>> attempts = java.util.stream.IntStream.range(0, 40)
                    .mapToObj(ignored -> (Callable<Boolean>) () -> {
                        try {
                            context.consume(budgetKind);
                            return true;
                        } catch (GenerationBudgetExceededException expected) {
                            return false;
                        }
                    })
                    .toList();
            long successfulReservations = executor.invokeAll(attempts).stream()
                    .filter(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .count();

            assertEquals(10, successfulReservations);
            assertEquals(10, context.used(budgetKind));
            assertEquals(0, context.remaining(budgetKind));
        }
    }

    private GenerationExecutionContext context(int budgetLimit, Duration taskTimeout, Clock clock) {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, budgetLimit);
        }
        return new GenerationExecutionContext(
                "task-1",
                11L,
                22L,
                clock.instant(),
                new GenerationExecutionLimits(
                        taskTimeout,
                        taskTimeout.compareTo(Duration.ofSeconds(30)) < 0 ? taskTimeout : Duration.ofSeconds(30),
                        Duration.ofMillis(500),
                        budgets
                ),
                clock
        );
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
