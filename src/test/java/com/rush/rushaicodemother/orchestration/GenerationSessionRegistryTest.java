package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertSame;

class GenerationSessionRegistryTest {

    @Test
    void sameApplicationMustAlwaysUseTheSameLockStripe() {
        GenerationSessionRegistry registry = newRegistry(defaultProperties(), new AtomicLong());

        assertSame(registry.lock(42L), registry.lock(42L));
    }

    @Test
    void lockObjectsMustRemainBoundedAcrossDistinctApplicationIds() {
        GenerationSessionProperties properties = defaultProperties();
        properties.setLockStripes(8);
        GenerationSessionRegistry registry = newRegistry(properties, new AtomicLong());
        Set<Object> distinctLocks = Collections.newSetFromMap(new IdentityHashMap<>());

        for (long appId = 1; appId <= 10_000; appId++) {
            distinctLocks.add(registry.lock(appId));
        }

        assertThat(distinctLocks).hasSize(8);
    }

    @Test
    void differentApplicationsMaySafelyShareALockStripe() {
        GenerationSessionProperties properties = defaultProperties();
        properties.setLockStripes(2);
        GenerationSessionRegistry registry = newRegistry(properties, new AtomicLong());

        assertSame(registry.lock(1L), registry.lock(3L));
    }

    @Test
    void applicationIdsMustBePositiveAcrossTheRegistryBoundary() {
        GenerationSessionRegistry registry = newRegistry(defaultProperties(), new AtomicLong());
        GenerationSession session = new GenerationSession(null);

        assertThatThrownBy(() -> registry.get(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> registry.lock(0L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> registry.put(-1L, session)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> registry.remove(0L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> registry.retainForReplay(-1L, session)).isInstanceOf(BusinessException.class);
    }

    @Test
    void compareAndRemoveMustNotDeleteAReplacementSession() {
        GenerationSessionRegistry registry = newRegistry(defaultProperties(), new AtomicLong());
        GenerationSession original = new GenerationSession(null);
        GenerationSession replacement = new GenerationSession(null);
        registry.put(1L, original);
        registry.put(1L, replacement);

        registry.remove(1L, original);

        assertSame(replacement, registry.get(1L));
    }

    @Test
    void replayExpiryForAnOldSessionMustNotRemoveANewerSession() {
        AtomicLong ticker = new AtomicLong();
        GenerationSessionRegistry registry = newRegistry(defaultProperties(), ticker);
        GenerationSession original = new GenerationSession(null);
        GenerationSession replacement = new GenerationSession(null);
        registry.put(1L, original);
        registry.retainForReplay(1L, original);
        registry.put(1L, replacement);

        ticker.addAndGet(Duration.ofSeconds(31).toNanos());
        registry.cleanupExpiredSessions();

        assertSame(replacement, registry.get(1L));
    }

    @Test
    void completedReplaySessionMustExpireAfterTheConfiguredRetention() {
        AtomicLong ticker = new AtomicLong();
        GenerationSessionRegistry registry = newRegistry(defaultProperties(), ticker);
        GenerationSession session = new GenerationSession(null);
        registry.put(1L, session);
        registry.retainForReplay(1L, session);

        ticker.addAndGet(Duration.ofSeconds(29).toNanos());
        assertSame(session, registry.get(1L));

        ticker.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(registry.cleanupExpiredSessions()).isEqualTo(1);
        assertThat(registry.get(1L)).isNull();
    }

    @Test
    void registryMustRejectNewApplicationsAtTheConfiguredCapacity() {
        GenerationSessionProperties properties = defaultProperties();
        properties.setMaxTrackedSessions(2);
        GenerationSessionRegistry registry = newRegistry(properties, new AtomicLong());
        registry.put(1L, new GenerationSession(null));
        registry.put(2L, new GenerationSession(null));

        assertThatThrownBy(() -> registry.put(3L, new GenerationSession(null)))
                .isInstanceOf(GenerationSessionCapacityExceededException.class)
                .hasMessageContaining("capacity of 2");
        assertThat(registry.trackedSessionCount()).isEqualTo(2);
    }

    @Test
    void capacityCheckMustReclaimExpiredReplaySessionsBeforeRejecting() {
        AtomicLong ticker = new AtomicLong();
        GenerationSessionProperties properties = defaultProperties();
        properties.setMaxTrackedSessions(1);
        GenerationSessionRegistry registry = newRegistry(properties, ticker);
        GenerationSession completed = new GenerationSession(null);
        registry.put(1L, completed);
        registry.retainForReplay(1L, completed);
        ticker.addAndGet(Duration.ofSeconds(31).toNanos());

        GenerationSession current = new GenerationSession(null);
        registry.put(2L, current);

        assertThat(registry.get(1L)).isNull();
        assertSame(current, registry.get(2L));
        assertThat(registry.trackedSessionCount()).isEqualTo(1);
    }

    private GenerationSessionRegistry newRegistry(GenerationSessionProperties properties, AtomicLong ticker) {
        return new GenerationSessionRegistry(properties, ticker::get);
    }

    private GenerationSessionProperties defaultProperties() {
        return new GenerationSessionProperties();
    }
}