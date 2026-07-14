package com.rush.rushaicodemother.application.app;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.dto.app.AppQueryRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppQueryApplicationServiceTest {

    private AppPersistenceService appPersistenceService;
    private AppViewAssembler appViewAssembler;
    private AppQueryApplicationService service;

    @BeforeEach
    void setUp() {
        appPersistenceService = mock(AppPersistenceService.class);
        appViewAssembler = mock(AppViewAssembler.class);
        service = new AppQueryApplicationService(appPersistenceService, appViewAssembler);
        Page<App> databasePage = new Page<>(1, 10, 0);
        databasePage.setRecords(List.of());
        when(appPersistenceService.pageActiveApps(org.mockito.ArgumentMatchers.any(AppQueryRequest.class)))
                .thenReturn(databasePage);
        when(appViewAssembler.toViewList(List.of())).thenReturn(List.of());
    }

    @Test
    void mineQueryMustOverrideUserScopeWithoutMutatingRequest() {
        AppQueryRequest source = new AppQueryRequest();
        source.setUserId(999L);
        source.setPriority(7);

        service.listMine(source, 42L);

        ArgumentCaptor<AppQueryRequest> captor = ArgumentCaptor.forClass(AppQueryRequest.class);
        verify(appPersistenceService).pageActiveApps(captor.capture());
        assertEquals(42L, captor.getValue().getUserId());
        assertEquals(7, captor.getValue().getPriority());
        assertEquals(999L, source.getUserId());
    }

    @Test
    void featuredQueryMustOverridePriorityWithoutMutatingRequest() {
        AppQueryRequest source = new AppQueryRequest();
        source.setPriority(1);
        source.setUserId(8L);

        service.listFeatured(source);

        ArgumentCaptor<AppQueryRequest> captor = ArgumentCaptor.forClass(AppQueryRequest.class);
        verify(appPersistenceService).pageActiveApps(captor.capture());
        assertEquals(AppConstant.GOOD_APP_PRIORITY, captor.getValue().getPriority());
        assertEquals(8L, captor.getValue().getUserId());
        assertEquals(1, source.getPriority());
    }
}
