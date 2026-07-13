package com.rush.rushaicodemother.service.impl;

import com.mybatisflex.core.query.QueryWrapper;
import com.rush.rushaicodemother.model.entity.AppDatabaseResource;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AppDatabaseResourceServiceImplTest {

    @Test
    void shouldLoadActiveResourcesWithOneBatchQuery() {
        AppDatabaseResourceServiceImpl service = spy(new AppDatabaseResourceServiceImpl());
        AppDatabaseResource latestForFirstApp = resource(11L, 1L, LocalDateTime.of(2026, 7, 10, 12, 0));
        AppDatabaseResource olderForFirstApp = resource(10L, 1L, LocalDateTime.of(2026, 7, 9, 12, 0));
        AppDatabaseResource resourceForSecondApp = resource(20L, 2L, LocalDateTime.of(2026, 7, 8, 12, 0));
        doReturn(List.of(latestForFirstApp, olderForFirstApp, resourceForSecondApp))
                .when(service).list(any(QueryWrapper.class));

        Map<Long, AppDatabaseResource> result = service.getActiveResourceMapByAppIds(List.of(1L, 2L));

        assertSame(latestForFirstApp, result.get(1L));
        assertSame(resourceForSecondApp, result.get(2L));
        verify(service, times(1)).list(any(QueryWrapper.class));
    }

    @Test
    void shouldSkipDatabaseQueryWhenNoValidAppIdExists() {
        AppDatabaseResourceServiceImpl service = spy(new AppDatabaseResourceServiceImpl());

        Map<Long, AppDatabaseResource> result = service.getActiveResourceMapByAppIds(List.of(-1L, 0L));

        assertTrue(result.isEmpty());
        verify(service, never()).list(any(QueryWrapper.class));
    }

    private AppDatabaseResource resource(Long id, Long appId, LocalDateTime createTime) {
        AppDatabaseResource resource = new AppDatabaseResource();
        resource.setId(id);
        resource.setAppId(appId);
        resource.setCreateTime(createTime);
        resource.setStatus("active");
        return resource;
    }
}