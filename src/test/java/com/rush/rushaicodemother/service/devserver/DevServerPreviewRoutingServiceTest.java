package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRecord;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRegistry;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevServerPreviewRoutingServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    /** 会话记录里的工作区目录必须真实存在，否则路由会以「工作区已消失」为由拒绝。 */
    @TempDir
    Path workspaceDirectory;

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

    /**
     * 本地路由直连回环、不经过内部跃点，因此活跃度必须在本地分支记账。
     *
     * <p>漏掉这一处，单节点部署下正在被观看的预览会被空闲回收误杀。</p>
     */
    @Test
    void localRouteMustReportActivityToPostponeIdleReclamation() {
        when(manager.getPort(21L)).thenReturn(5180);
        when(registry.findByAppId(21L)).thenReturn(Optional.of(record(
                "preview-node-a", "owner-a", DevServerSessionState.RUNNING, 5180, NOW.plusSeconds(30)
        )));

        service.requireRunningRoute(21L);

        verify(manager).touchSession(21L);
    }

    /** 跨节点转发时，非所有者节点不得为它管不到的会话续命。 */
    @Test
    void remoteRouteMustNotReportActivityOnTheForwardingNode() {
        when(manager.getPort(21L)).thenReturn(null);
        when(registry.findByAppId(21L)).thenReturn(Optional.of(record(
                "preview-node-b", "owner-b", DevServerSessionState.RUNNING, 5180, NOW.plusSeconds(30)
        )));
        when(nodeRouteResolver.resolve("preview-node-b"))
                .thenReturn(URI.create("http://preview-node-b:8123/api"));

        service.requireRunningRoute(21L);

        verify(manager, never()).touchSession(any());
    }

    /** 跨节点流量最终落到所有者节点的这一跳，是远端预览唯一的活跃度来源。 */
    @Test
    void internalHopMustReportActivityOnTheOwnerNode() {
        when(manager.getPort(21L)).thenReturn(5180);
        when(registry.findByAppId(21L)).thenReturn(Optional.of(record(
                "preview-node-a", "owner-a", DevServerSessionState.RUNNING, 5180, NOW.plusSeconds(30)
        )));

        service.requireLocalRunningPort(21L);

        verify(manager).touchSession(21L);
    }

    /** 校验失败时不得记账，否则无效访问也能给会话续命。 */
    @Test
    void rejectedInternalHopMustNotReportActivity() {
        when(manager.getPort(21L)).thenReturn(5180);
        when(registry.findByAppId(21L)).thenReturn(Optional.of(record(
                "preview-node-a", "different-owner", DevServerSessionState.RUNNING, 5180,
                NOW.plusSeconds(30)
        )));

        assertThrows(BusinessException.class, () -> service.requireLocalRunningPort(21L));

        verify(manager, never()).touchSession(any());
    }

    /**
     * 工作区已被发布移走的会话必须立即被拒，而不是等心跳回收。
     *
     * <p>这是暂定预览把 Dev Server 持有权上提到发布点之后必须补上的判据：租约所有者可以不变
     * （同一 worker 连续跑两个执行纪元），而上一个纪元的工作区已经被发布整体移走。
     * Linux 上进程会继续持有旧 inode，于是预览既不报错也不更新 —— 用户看到一份无声的过期内容。</p>
     */
    @Test
    void sessionWhoseWorkspaceWasPublishedAwayMustBeRejectedImmediately() {
        when(manager.getPort(21L)).thenReturn(5180);
        when(registry.findByAppId(21L)).thenReturn(Optional.of(record(
                "preview-node-a", "owner-a", DevServerSessionState.RUNNING, 5180,
                NOW.plusSeconds(30), workspaceDirectory.resolve("moved-away")
        )));

        assertTrue(service.findCurrent(21L).isEmpty()
                || !service.findCurrent(21L).orElseThrow().running());
        assertThrows(BusinessException.class, () -> service.requireRunningRoute(21L));
        assertThrows(BusinessException.class, () -> service.requireLocalRunningPort(21L));
        verify(manager, never()).touchSession(any());
    }

    /**
     * 远端会话的目录在另一个节点上，本地文件系统看不到，不得因此判定失效。
     *
     * <p>漏掉这个分支会把所有跨节点预览误判为工作区已消失，多实例部署下预览全线不可用。</p>
     */
    @Test
    void remoteSessionMustNotBeJudgedByLocalFilesystem() {
        when(manager.getPort(21L)).thenReturn(null);
        when(registry.findByAppId(21L)).thenReturn(Optional.of(record(
                "preview-node-b", "owner-b", DevServerSessionState.RUNNING, 5180,
                NOW.plusSeconds(30), workspaceDirectory.resolve("only-exists-on-node-b")
        )));
        when(nodeRouteResolver.resolve("preview-node-b"))
                .thenReturn(URI.create("http://preview-node-b:8123/api"));

        DevServerPreviewRoute route = service.requireRunningRoute(21L);

        assertFalse(route.local());
        assertEquals("preview-node-b", route.nodeId());
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
        return record(nodeId, leaseOwner, state, port, leaseUntil, workspaceDirectory);
    }

    private DevServerSessionRecord record(
            String nodeId,
            String leaseOwner,
            DevServerSessionState state,
            int port,
            Instant leaseUntil,
            Path projectDirectory
    ) {
        return new DevServerSessionRecord(
                21L,
                7L,
                nodeId,
                leaseOwner,
                state,
                port,
                projectDirectory,
                "container",
                List.of("sandbox-21"),
                leaseUntil,
                3L
        );
    }
}
