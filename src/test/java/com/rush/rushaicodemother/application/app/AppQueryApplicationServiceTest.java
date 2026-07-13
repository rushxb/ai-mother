package com.rush.rushaicodemother.application.app;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.dto.app.AppQueryRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.service.AppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppQueryApplicationServiceTest {

    private AppService appService;
    private AppViewAssembler appViewAssembler;
    private AppQueryApplicationService service;
    private QueryWrapper queryWrapper;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        appService = mock(AppService.class);
        appViewAssembler = mock(AppViewAssembler.class);
        service = new AppQueryApplicationService(appService, appViewAssembler);
        queryWrapper = QueryWrapper.create();
        Page<App> databasePage = new Page<>(1, 10, 0);
        databasePage.setRecords(List.of());
        when(appService.getQueryWrapper(any(AppQueryRequest.class))).thenReturn(queryWrapper);
        when(appService.page(any(Page.class), same(queryWrapper))).thenReturn(databasePage);
        when(appViewAssembler.toViewList(List.of())).thenReturn(List.of());
    }

    @Test
    void mineQueryMustOverrideUserScopeWithoutMutatingRequest() {
        AppQueryRequest source = new AppQueryRequest();
        source.setUserId(999L);
        source.setPriority(7);

        service.listMine(source, 42L);

        ArgumentCaptor<AppQueryRequest> captor = ArgumentCaptor.forClass(AppQueryRequest.class);
        verify(appService).getQueryWrapper(captor.capture());
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
        verify(appService).getQueryWrapper(captor.capture());
        assertEquals(AppConstant.GOOD_APP_PRIORITY, captor.getValue().getPriority());
        assertEquals(8L, captor.getValue().getUserId());
        assertEquals(1, source.getPriority());
    }
}