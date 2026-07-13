package com.rush.rushaicodemother.application.app;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.DevServerStatusVO;
import com.rush.rushaicodemother.service.AppService;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import com.rush.rushaicodemother.service.devserver.DevServerStartResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppDevServerApplicationServiceTest {

    private AppService appService;
    private DevServerManager devServerManager;
    private AppDevServerApplicationService service;

    @BeforeEach
    void setUp() {
        appService = mock(AppService.class);
        devServerManager = mock(DevServerManager.class);
        service = new AppDevServerApplicationService(
                appService,
                devServerManager,
                new AppAccessPolicy()
        );
    }

    @Test
    void nonOwnerMustBeRejectedBeforeStartingProcess() {
        User actor = User.builder().id(1L).build();
        when(appService.getById(21L)).thenReturn(App.builder().id(21L).userId(2L).build());

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
        when(appService.getById(21L)).thenReturn(app);
        when(devServerManager.startDevServer(app, 1L)).thenReturn(new DevServerStartResult(5180, true));
        when(appService.updateById(any(App.class))).thenReturn(true);

        DevServerStatusVO result = service.start(21L, actor);

        ArgumentCaptor<App> updateCaptor = ArgumentCaptor.forClass(App.class);
        verify(appService).updateById(updateCaptor.capture());
        assertEquals(21L, updateCaptor.getValue().getId());
        assertEquals(5180, updateCaptor.getValue().getDevServerPort());
        assertEquals(5180, result.getPort());
        assertEquals(Boolean.TRUE, result.getRunning());
    }

    @Test
    void persistenceFailureMustStopSessionStartedByCurrentCall() {
        User actor = User.builder().id(1L).build();
        App app = App.builder().id(21L).userId(1L).devServerPort(5173).build();
        when(appService.getById(21L)).thenReturn(app);
        when(devServerManager.startDevServer(app, 1L)).thenReturn(new DevServerStartResult(5180, true));
        when(appService.updateById(any(App.class))).thenReturn(false);

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
        when(appService.getById(21L)).thenReturn(app);
        when(devServerManager.startDevServer(app, 1L)).thenReturn(new DevServerStartResult(5180, true));
        when(appService.updateById(any(App.class))).thenReturn(false);
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
        when(appService.getById(21L)).thenReturn(app);
        when(devServerManager.startDevServer(app, 1L)).thenReturn(new DevServerStartResult(5180, false));
        when(appService.updateById(any(App.class))).thenReturn(false);

        assertThrows(BusinessException.class, () -> service.start(21L, actor));

        verify(devServerManager, never()).stopDevServer(21L);
    }

    @Test
    void requireProxyPortMustUseCurrentManagerPortInsteadOfPersistedPort() {
        User actor = User.builder().id(1L).build();
        when(appService.getById(21L)).thenReturn(
                App.builder().id(21L).userId(1L).devServerPort(70000).build()
        );
        when(devServerManager.getPort(21L)).thenReturn(5180);

        assertEquals(5180, service.requireProxyPort(21L, actor));
    }

    @Test
    void requireProxyPortMustRejectStoppedServerEvenWhenDatabaseContainsPort() {
        User actor = User.builder().id(1L).build();
        when(appService.getById(21L)).thenReturn(
                App.builder().id(21L).userId(1L).devServerPort(5173).build()
        );
        when(devServerManager.getPort(21L)).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.requireProxyPort(21L, actor)
        );

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
    }

    @Test
    void statusMustPreferRuntimePortWhenRunning() {
        User actor = User.builder().id(1L).build();
        when(appService.getById(21L)).thenReturn(
                App.builder().id(21L).userId(1L).devServerPort(5173).build()
        );
        when(devServerManager.getPort(21L)).thenReturn(5180);

        DevServerStatusVO status = service.getStatus(21L, actor);

        assertTrue(status.getRunning());
        assertEquals(5180, status.getPort());
        assertEquals("http://localhost:5180", status.getPreviewUrl());
    }

    @Test
    void statusMustRetainPersistedPortForStoppedServerDisplay() {
        User actor = User.builder().id(1L).build();
        when(appService.getById(21L)).thenReturn(
                App.builder().id(21L).userId(1L).devServerPort(5173).build()
        );
        when(devServerManager.getPort(21L)).thenReturn(null);

        DevServerStatusVO status = service.getStatus(21L, actor);

        assertEquals(Boolean.FALSE, status.getRunning());
        assertEquals(5173, status.getPort());
        assertEquals("stopped", status.getStatus());
    }
}
