package com.rush.rushaicodemother.orchestration.runtime.model;

import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootModelRetryPolicyTest {

    @Test
    void retryDelayMustGrowExponentiallyAndStopAtConfiguredMaximum() {
        RootModelRetryPolicy policy = policy(Duration.ofSeconds(3), Duration.ofSeconds(20));

        assertEquals(Duration.ofSeconds(3), policy.decide(0, null).delay());
        assertEquals(Duration.ofSeconds(6), policy.decide(1, null).delay());
        assertEquals(Duration.ofSeconds(12), policy.decide(2, null).delay());
        assertEquals(Duration.ofSeconds(20), policy.decide(3, null).delay());
        assertEquals(Duration.ofSeconds(20), policy.decide(20, null).delay());
    }

    @Test
    void retryMustBeRejectedWhenRootAttemptBudgetIsExhausted() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        GenerationExecutionContext context = context(
                clock, Duration.ofSeconds(30), Duration.ofSeconds(1), 1);
        context.consume(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);

        RootModelRetryPolicy.Decision decision = policy(
                Duration.ofSeconds(3), Duration.ofSeconds(20)).decide(0, context);

        assertFalse(decision.retryAllowed());
        assertEquals(RootModelRetryPolicy.Rejection.BUDGET_EXHAUSTED, decision.rejection());
    }

    @Test
    void retryMustBeRejectedWhenBackoffCannotLeaveMinimumOperationWindow() {
        MutableClock clock = new MutableClock(Instant.EPOCH.plusSeconds(6));
        GenerationExecutionContext context = context(
                clock, Duration.ofSeconds(10), Duration.ofSeconds(2), 2);

        RootModelRetryPolicy.Decision decision = policy(
                Duration.ofSeconds(3), Duration.ofSeconds(20)).decide(0, context);

        assertFalse(decision.retryAllowed());
        assertEquals(RootModelRetryPolicy.Rejection.DEADLINE_EXHAUSTED, decision.rejection());
    }

    @Test
    void retryDelayMustBeClampedToTheAffordableDeadlineWindow() {
        MutableClock clock = new MutableClock(Instant.EPOCH.plusSeconds(4));
        GenerationExecutionContext context = context(
                clock, Duration.ofSeconds(10), Duration.ofSeconds(2), 2);

        RootModelRetryPolicy.Decision decision = policy(
                Duration.ofSeconds(3), Duration.ofSeconds(20)).decide(2, context);

        assertTrue(decision.retryAllowed());
        assertEquals(Duration.ofSeconds(4), decision.delay());
    }

    private RootModelRetryPolicy policy(Duration minimum, Duration maximum) {
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();
        properties.setRootModelRetryMinDelay(minimum);
        properties.setRootModelRetryMaxDelay(maximum);
        properties.setRootModelRetryJitter(0);
        return new RootModelRetryPolicy(properties, () -> 0.5);
    }

    private GenerationExecutionContext context(MutableClock clock,
                                               Duration taskTimeout,
                                               Duration minimumOperationTimeout,
                                               int rootAttempts) {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 3);
        }
        budgets.put(GenerationBudgetKind.ROOT_MODEL_ATTEMPT, rootAttempts);
        GenerationExecutionLimits limits = new GenerationExecutionLimits(
                taskTimeout,
                taskTimeout,
                minimumOperationTimeout,
                budgets
        );
        return new GenerationExecutionContext(
                "root-retry-test", 1L, 1L, Instant.EPOCH, limits, clock);
    }

    private static final class MutableClock extends Clock {

        private final Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
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
