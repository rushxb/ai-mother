package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxProcessPlan;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionClaimResult;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableDevServerSessionLeaseCoordinatorTest {

    @Test
    void sandboxManifestMustBePersistedWhileTheSessionIsStillStarting() {
        DevServerSessionRegistry registry = mock(DevServerSessionRegistry.class);
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setNodeId("node-a");
        Instant now = Instant.parse("2026-07-17T08:00:00Z");
        DevServerNodeIdentityProvider identity = new DevServerNodeIdentityProvider(properties);
        DurableDevServerSessionLeaseCoordinator coordinator =
                new DurableDevServerSessionLeaseCoordinator(
                        registry, properties, identity, Clock.fixed(now, ZoneId.of("UTC")));
        when(registry.recordStartingResources(any(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(true);
        SandboxProcessPlan plan = new SandboxProcessPlan(
                "container", Path.of("project"), List.of(), Map.of(), Set.of(),
                "dev", List.of(), List.of("gateway", "dev"));

        coordinator.onPlanPrepared(11L, plan);

        verify(registry).recordStartingResources(
                org.mockito.ArgumentMatchers.eq(11L),
                anyString(),
                org.mockito.ArgumentMatchers.eq("container"),
                org.mockito.ArgumentMatchers.eq(List.of("gateway", "dev")),
                org.mockito.ArgumentMatchers.eq(now),
                org.mockito.ArgumentMatchers.eq(now.plus(properties.getLeaseDuration()))
        );
    }

    @Test
    void databaseOutageMustFenceTheLocalProcessWhenItsLastLeaseDeadlineExpires() {
        DevServerSessionRegistry registry = mock(DevServerSessionRegistry.class);
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setNodeId("node-a");
        properties.setLeaseDuration(Duration.ofSeconds(30));
        MutableClock clock = new MutableClock(Instant.parse("2026-07-17T08:00:00Z"));
        DevServerNodeIdentityProvider identity = new DevServerNodeIdentityProvider(properties);
        DurableDevServerSessionLeaseCoordinator coordinator =
                new DurableDevServerSessionLeaseCoordinator(registry, properties, identity, clock);
        when(registry.claimStarting(any(), any(), any(), anyInt()))
                .thenReturn(DevServerSessionClaimResult.ACQUIRED);
        when(registry.renew(any(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertEquals(
                DevServerSessionClaimResult.ACQUIRED,
                coordinator.claimStarting(11L, 7L, Path.of("project"), 5180)
        );
        assertEquals(
                DevServerSessionLeaseCoordinator.LeaseStatus.RETRYABLE_FAILURE,
                coordinator.renew(11L)
        );

        clock.advance(Duration.ofSeconds(31));

        assertEquals(
                DevServerSessionLeaseCoordinator.LeaseStatus.LOST,
                coordinator.renew(11L)
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
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
