package com.rush.rushaicodemother.orchestration.runtime.task;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class GenerationTaskMaintenanceServiceTest {

    @Test
    void everyTickMustHeartbeatButRecoveryMustRespectItsOwnBoundedInterval() {
        GenerationTaskLeaseCoordinator coordinator = mock(GenerationTaskLeaseCoordinator.class);
        GenerationTaskRecoveryService recoveryService = mock(GenerationTaskRecoveryService.class);
        GenerationTaskLeaseProperties properties = new GenerationTaskLeaseProperties();
        properties.setRecoveryScanInterval(Duration.ofSeconds(15));
        MutableClock clock = new MutableClock(Instant.parse("2026-07-16T05:00:00Z"));
        GenerationTaskMaintenanceService service = new GenerationTaskMaintenanceService(
                coordinator, recoveryService, properties, clock);

        service.runMaintenance();
        service.runMaintenance();
        clock.advance(Duration.ofSeconds(15));
        service.runMaintenance();

        verify(coordinator, times(3)).heartbeatTrackedTasks();
        verify(recoveryService, times(2)).recoverExpiredTasks();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
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
            return now;
        }
    }
}
