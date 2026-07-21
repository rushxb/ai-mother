package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRecord;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRegistry;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DevServerPreviewRoutingServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    private DevServerManager manager;
    private DevServerSessionRegistry registry;
    private DevServerNodeIdentityProvider identityProvider;
    private DevServerNodeRouteResolver nodeRouteResolver;
    private DevServerPreviewRoutingService service;

    @BeforeEach
    void setUp() {
        manager = mock(DevServerManager.class);
        registry = mock(DevServerSessionRegistry.class);
        identityProvider = mock(DevServerNodeIdentityProvider.class);
        nodeRouteResolver = mock(DevServerNodeRouteResolver.class);
        when(identityProvider.nodeId()).thenReturn("preview-node-a");
        when(identityProvider.ownerId()).thenReturn("owner-a");
        service = new DevServerPreviewRoutingService(
                manager,
                registry,
                identityProvider,
                nodeRouteResolver,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldRouteAValidLocalOwner() {
        when(manager.getPort(21L)).thenReturn(5180);
        when(registry.findByAppId(21L)).thenReturn(Optional.of(record(
                "preview-node-a", "owner-a", DevServerSessionState.RUNNING, 5180, NOW.plusSeconds(30)
        )));

        DevServerPreviewRoute route = service.requireRunningRoute(21L);

        assertTrue(route.local());
        assertEquals(5180, route.port());
        assertEquals(5180, service.requireLocalRunningPort(21L));
    }

    @Test
    void shouldRouteToTheDurableRemoteOwnerEvenWhenAnOrphanLocalProcessExists() {
        when(manager.getPort(21L)).thenReturn(5199);
        when(registry.findByAppId(21L)).thenReturn(Optional.of(record(
                "preview-node-b", "owner-b", DevServerSessionState.RUNNING, 5180, NOW.plusSeconds(30)
        )));
        when(nodeRouteResolver.resolve("preview-node-b"))
                .thenReturn(URI.create("http://preview-node-b:8123/api"));

        DevServerPreviewRoute route = service.requireRunningRoute(21L);

        assertFalse(route.local());
        assertEquals("preview-node-b", route.nodeId());
        assertEquals(5180, route.port());
    }

    @Test
    void shouldFailClosedWhenTheLocalLeaseExpired() {
        when(manager.getPort(21L)).thenReturn(5180);
        when(registry.findByAppId(21L)).thenReturn(Optional.of(record(
                "preview-node-a", "owner-a", DevServerSessionState.RUNNING, 5180, NOW
        )));

        DevServerPreviewSession session = service.findCurrent(21L).orElseThrow();

        assertFalse(session.running());
        assertEquals("unavailable", session.status());
        assertThrows(BusinessException.class, () -> service.requireRunningRoute(21L));
    }

    @Test
    void shouldFailClosedWhenDurableOwnerHasNoMatchingLocalProcess() {
        when(manager.getPort(21L)).thenReturn(null);
        when(registry.findByAppId(21L)).thenReturn(Optional.of(record(
                "preview-node-a", "owner-a", DevServerSessionState.RUNNING, 5180, NOW.plusSeconds(30)
        )));

        DevServerPreviewSession session = service.findCurrent(21L).orElseThrow();

        assertFalse(session.running());
        assertEquals("unavailable", session.status());
        assertThrows(BusinessException.class, () -> service.requireLocalRunningPort(21L));
    }

    @Test
    void internalHopMustFenceNodeOwnerAndPort() {
        when(manager.getPort(21L)).thenReturn(5180);
        when(registry.findByAppId(21L)).thenReturn(Optional.of(record(
                "preview-node-a", "different-owner", DevServerSessionState.RUNNING, 5180,
                NOW.plusSeconds(30)
        )));

        assertThrows(BusinessException.class, () -> service.requireLocalRunningPort(21L));
    }

    private DevServerSessionRecord record(
            String nodeId,
            String leaseOwner,
            DevServerSessionState state,
            int port,
            Instant leaseUntil
    ) {
        return new DevServerSessionRecord(
                21L,
                7L,
                nodeId,
                leaseOwner,
                state,
                port,
                Path.of("generated", "21"),
                "container",
                List.of("sandbox-21"),
                leaseUntil,
                3L
        );
    }
}
