package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.infrastructure.sandbox.GeneratedCodeProcessSandbox;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRecord;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRegistry;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevServerSessionRecoveryServiceTest {

    private DevServerSessionRegistry registry;
    private GeneratedCodeProcessSandbox sandbox;
    private DevServerSessionRecoveryService service;
    private DevServerSessionRecord candidate;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-07-17T08:00:00Z");
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setNodeId("node-b");
        DevServerNodeIdentityProvider identity = new DevServerNodeIdentityProvider(properties);
        registry = mock(DevServerSessionRegistry.class);
        sandbox = mock(GeneratedCodeProcessSandbox.class);
        service = new DevServerSessionRecoveryService(
                registry, properties, identity, sandbox, Clock.fixed(now, ZoneOffset.UTC));
        candidate = new DevServerSessionRecord(
                11L,
                7L,
                "node-a",
                "old-owner",
                DevServerSessionState.RUNNING,
                5180,
                Path.of("project").toAbsolutePath(),
                "container",
                List.of("gateway", "dev-server"),
                now.minusSeconds(1),
                4L
        );
        when(registry.findExpired(any(), any(Integer.class))).thenReturn(List.of(candidate));
        when(registry.claimRecovery(any(), anyString(), anyString(), any(), any())).thenReturn(true);
        when(registry.markStopped(any(), anyString(), any(), anyString())).thenReturn(true);
    }

    @Test
    void claimedOrphanMustCleanAllSandboxResourcesBeforeTerminalizing() {
        assertEquals(1, service.recoverExpiredSessions());

        verify(sandbox).cleanupResources("container", List.of("gateway", "dev-server"));
        verify(registry).markStopped(any(), anyString(), any(), anyString());
    }

    @Test
    void cleanupFailureMustLeaveRecoveryLeaseForADeadlineBoundRetry() {
        doThrow(new IllegalStateException("docker unavailable"))
                .when(sandbox).cleanupResources("container", List.of("gateway", "dev-server"));

        assertEquals(0, service.recoverExpiredSessions());

        verify(registry, never()).markStopped(any(), anyString(), any(), anyString());
    }
}
