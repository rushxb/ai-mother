package com.rush.rushaicodemother.application.app;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.TenantRole;
import com.rush.rushaicodemother.model.vo.DevServerStatusVO;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewPathFactory;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewRoute;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewRoutingService;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewSession;
import com.rush.rushaicodemother.service.devserver.DevServerStartResult;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionState;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppDevServerApplicationServiceTest {

    private AppPersistenceService appPersistenceService;
    private DevServerManager devServerManager;
    private DevServerPreviewRoutingService previewRoutingService;
    private TenantAuthorizationService tenantAuthorizationService;
    private AppDevServerApplicationService service;

    @BeforeEach
    void setUp() {
        appPersistenceService = mock(AppPersistenceService.class);
        devServerManager = mock(DevServerManager.class);
        previewRoutingService = mock(DevServerPreviewRoutingService.class);
        tenantAuthorizationService = mock(TenantAuthorizationService.class);
        when(previewRoutingService.findCurrent(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Optional.empty());
        service = new AppDevServerApplicationService(
                appPersistenceService,
                devServerManager,
                previewRoutingService,
                previewPathFactory(),
                new AppAccessPolicy(tenantAuthorizationService)
        );
    }

    @Test
    void nonOwnerMustBeRejectedBeforeStartingProcess() {
        User actor = User.builder().id(1L).build();
        when(appPersistenceService.findActiveById(21L))
                .thenReturn(App.builder().id(21L).userId(2L).tenantId(100L).build());
        doThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR, "denied"))
                .when(tenantAuthorizationService)
                .requireRole(eq(100L), eq(1L), eq(TenantRole.DEVELOPER), anyString());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.start(21L, actor)
        );

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
        verifyNoInteractions(devServerManager);
    }

    @Test
    void startMustPersistNewPortAndReturnStatus() {
        User actor = User.builder().id(1L).build();
        App app = App.builder().id(21L).userId(1L).devServerPort(5173).build();
        when(appPersistenceService.findActiveById(21L)).thenReturn(app);
        when(devServerManager.startDevServer(app, 1L)).thenReturn(new DevServerStartResult(5180, true));

        DevServerStatusVO result = service.start(21L, actor);

        verify(appPersistenceService).updateDevServerPort(21L, 5180);
        assertEquals(5180, result.getPort());
        assertEquals(Boolean.TRUE, result.getRunning());
    }

    @Test
    void persistenceFailureMustStopSessionStartedByCurrentCall() {
        User actor = User.builder().id(1L).build();
        App app = App.builder().id(21L).userId(1L).devServerPort(5173).build();
        when(appPersistenceService.findActiveById(21L)).thenReturn(app);
        when(devServerManager.startDevServer(app, 1L)).thenReturn(new DevServerStartResult(5180, true));
        doThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "保存 Dev Server 端口失败"))
                .when(appPersistenceService).updateDevServerPort(21L, 5180);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.start(21L, actor)
        );

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        verify(devServerManager).stopDevServer(21L);
    }

    @Test
    void stopFailureMustBeSuppressedOnPersistenceFailure() {
        User actor = User.builder().id(1L).build();
        App app = App.builder().id(21L).userId(1L).devServerPort(5173).build();
        RuntimeException stopFailure = new IllegalStateException("stop failed");
        when(appPersistenceService.findActiveById(21L)).thenReturn(app);
        when(devServerManager.startDevServer(app, 1L)).thenReturn(new DevServerStartResult(5180, true));
        doThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "保存 Dev Server 端口失败"))
                .when(appPersistenceService).updateDevServerPort(21L, 5180);
        doThrow(stopFailure).when(devServerManager).stopDevServer(21L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.start(21L, actor)
        );

        assertEquals(1, exception.getSuppressed().length);
        assertSame(stopFailure, exception.getSuppressed()[0]);
    }

    @Test
    void persistenceFailureMustNotStopReusedSession() {
        User actor = User.builder().id(1L).build();
        App app = App.builder().id(21L).userId(1L).devServerPort(5173).build();
        when(appPersistenceService.findActiveById(21L)).thenReturn(app);
        when(devServerManager.startDevServer(app, 1L)).thenReturn(new DevServerStartResult(5180, false));
        doThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "保存 Dev Server 端口失败"))
                .when(appPersistenceService).updateDevServerPort(21L, 5180);

        assertThrows(BusinessException.class, () -> service.start(21L, actor));

        verify(devServerManager, never()).stopDevServer(21L);
    }

    @Test
    void requireProxyRouteMustUseClusterAwareRoutingInsteadOfPersistedPort() {
        User actor = User.builder().id(1L).build();
        when(appPersistenceService.findActiveById(21L)).thenReturn(
                App.builder().id(21L).userId(1L).devServerPort(70000).build()
        );
        DevServerPreviewRoute route = DevServerPreviewRoute.remote(
                21L,
                "preview-node-b",
                5180,
                java.net.URI.create("http://preview-node-b:8123/api")
        );
        when(previewRoutingService.requireRunningRoute(21L)).thenReturn(route);

        assertSame(route, service.requireProxyRoute(21L, actor));
    }

    @Test
    void requireProxyRouteMustRejectStoppedServerEvenWhenDatabaseContainsPort() {
        User actor = User.builder().id(1L).build();
        when(appPersistenceService.findActiveById(21L)).thenReturn(
                App.builder().id(21L).userId(1L).devServerPort(5173).build()
        );
        when(previewRoutingService.requireRunningRoute(21L))
                .thenThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "Dev Server not running"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.requireProxyRoute(21L, actor)
        );

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
    }

    @Test
    void statusMustPreferRuntimePortWhenRunning() {
        User actor = User.builder().id(1L).build();
        when(appPersistenceService.findActiveById(21L)).thenReturn(
                App.builder().id(21L).userId(1L).devServerPort(5173).build()
        );
        when(previewRoutingService.findCurrent(21L)).thenReturn(Optional.of(
                new DevServerPreviewSession(
                        21L,
                        "preview-node-b",
                        5180,
                        DevServerSessionState.RUNNING,
                        false,
                        true
                )
        ));

        DevServerStatusVO status = service.getStatus(21L, actor);

        assertTrue(status.getRunning());
        assertEquals(5180, status.getPort());
        assertEquals("/api/app/dev-server/proxy/21/", status.getPreviewUrl());
    }

    @Test
    void statusMustRetainPersistedPortForStoppedServerDisplay() {
        User actor = User.builder().id(1L).build();
        when(appPersistenceService.findActiveById(21L)).thenReturn(
                App.builder().id(21L).userId(1L).devServerPort(5173).build()
        );
        when(previewRoutingService.findCurrent(21L)).thenReturn(Optional.empty());

        DevServerStatusVO status = service.getStatus(21L, actor);

        assertEquals(Boolean.FALSE, status.getRunning());
        assertEquals(5173, status.getPort());
        assertEquals("stopped", status.getStatus());
    }

    private DevServerPreviewPathFactory previewPathFactory() {
        ServerProperties properties = new ServerProperties();
        properties.getServlet().setContextPath("/api");
        return new DevServerPreviewPathFactory(properties);
    }
}
