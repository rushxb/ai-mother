package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.config.AppDatabaseResourceProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.AppDatabaseResource;
import com.rush.rushaicodemother.model.vo.AppDatabaseResourceVO;
import com.rush.rushaicodemother.service.database.AppDatabaseResourcePersistenceService;
import com.rush.rushaicodemother.service.database.AppDatabaseResourceViewConverter;
import com.rush.rushaicodemother.service.database.NewAppDatabaseResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppDatabaseResourceServiceImplTest {

    private AppDatabaseResourcePersistenceService persistenceService;
    private AppDatabaseResourceServiceImpl service;

    @BeforeEach
    void setUp() {
        persistenceService = mock(AppDatabaseResourcePersistenceService.class);
        AppDatabaseResourceProperties properties = new AppDatabaseResourceProperties();
        properties.setUrlScheme("http");
        properties.setDomain("localhost");
        properties.setDbEngine("SQLite");
        properties.setBackendRuntime("go");
        properties.setSqlExecutionPolicy("ask_every_time");
        service = new AppDatabaseResourceServiceImpl(
                persistenceService,
                new AppDatabaseResourceViewConverter(),
                properties
        );
    }

    @Test
    void enableMustBuildConfiguredResourceAndReturnOnlyView() {
        App app = app(7L, 9L, "Production App");
        AppDatabaseResource persisted = resource(101L, 7L);
        when(persistenceService.enableResource(any())).thenReturn(persisted);

        AppDatabaseResourceVO result = service.enableDatabase(app);

        assertEquals(101L, result.getId());
        assertEquals(7L, result.getAppId());
        assertTrue(result.getEnabled());
        ArgumentCaptor<NewAppDatabaseResource> captor = ArgumentCaptor.forClass(NewAppDatabaseResource.class);
        verify(persistenceService).enableResource(captor.capture());
        NewAppDatabaseResource command = captor.getValue();
        assertEquals(7L, command.appId());
        assertEquals(9L, command.userId());
        assertEquals("db7", command.resourceId());
        assertEquals("Production App Database", command.resourceName());
        assertEquals("http://db7.localhost", command.databaseUrl());
        assertEquals("SQLite", command.dbEngine());
        assertEquals("go", command.backendRuntime());
        assertEquals("ask_every_time", command.sqlExecutionPolicy());
    }

    @Test
    void enableMustRejectAppWithoutValidOwnerBeforePersistence() {
        App app = app(7L, null, "invalid");

        assertThrows(BusinessException.class, () -> service.enableDatabase(app));

        verifyNoInteractions(persistenceService);
    }

    @Test
    void invalidViewLookupMustNotReachPersistence() {
        assertNull(service.findActiveResourceView(null));
        assertNull(service.findActiveResourceView(0L));

        verifyNoInteractions(persistenceService);
    }

    @Test
    void batchViewLookupMustConvertEntitiesWithoutExposingThem() {
        AppDatabaseResource first = resource(101L, 1L);
        AppDatabaseResource second = resource(102L, 2L);
        when(persistenceService.findActiveByAppIds(any())).thenReturn(List.of(first, second));

        Map<Long, AppDatabaseResourceVO> result =
                service.findActiveResourceViews(Arrays.asList(null, 1L, 2L));

        assertEquals(2, result.size());
        assertEquals(101L, result.get(1L).getId());
        assertEquals(102L, result.get(2L).getId());
    }

    @Test
    void enabledResourceMustAppendConfiguredAndCorrectlySpelledInstruction() {
        App app = app(7L, 9L, "app");
        AppDatabaseResource persisted = resource(101L, 7L);
        when(persistenceService.findActiveByAppId(7L)).thenReturn(persisted);

        String result = service.appendGenerationInstructionIfEnabled(app, "生成管理后台");

        assertTrue(result.startsWith("生成管理后台"));
        assertTrue(result.contains("Rush Database"));
        assertTrue(result.contains("go + SQLite"));
        assertTrue(result.contains("http://db7.localhost"));
        assertFalse(result.contains("Rsh Database"));
        assertFalse(result.contains("SqlLite"));
    }

    @Test
    void missingResourceMustKeepOriginalPrompt() {
        App app = app(7L, 9L, "app");
        String original = "生成静态页面";
        when(persistenceService.findActiveByAppId(7L)).thenReturn(null);

        assertSame(original, service.appendGenerationInstructionIfEnabled(app, original));
        verify(persistenceService, never()).enableResource(any());
    }

    private App app(Long id, Long userId, String appName) {
        App app = new App();
        app.setId(id);
        app.setUserId(userId);
        app.setAppName(appName);
        return app;
    }

    private AppDatabaseResource resource(Long id, Long appId) {
        AppDatabaseResource resource = new AppDatabaseResource();
        resource.setId(id);
        resource.setAppId(appId);
        resource.setResourceId("db" + appId);
        resource.setResourceName("app Database");
        resource.setDatabaseUrl("http://db" + appId + ".localhost");
        resource.setDbEngine("SQLite");
        resource.setBackendRuntime("go");
        resource.setSqlExecutionPolicy("ask_every_time");
        resource.setStatus("active");
        return resource;
    }
}
