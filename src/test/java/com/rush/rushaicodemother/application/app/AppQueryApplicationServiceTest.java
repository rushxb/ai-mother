package com.rush.rushaicodemother.application.app;

import com.mybatisflex.core.paginate.Page;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.dto.app.AppQueryRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppQueryApplicationServiceTest {

    private AppPersistenceService appPersistenceService;
    private AppViewAssembler appViewAssembler;
    private AppAccessPolicy appAccessPolicy;
    private AppQueryApplicationService service;

    @BeforeEach
    void setUp() {
        appPersistenceService = mock(AppPersistenceService.class);
        appViewAssembler = mock(AppViewAssembler.class);
        appAccessPolicy = mock(AppAccessPolicy.class);
        service = new AppQueryApplicationService(
                appPersistenceService, appViewAssembler, appAccessPolicy);
        Page<App> databasePage = new Page<>(1, 10, 0);
        databasePage.setRecords(List.of());
        when(appPersistenceService.pageActiveApps(org.mockito.ArgumentMatchers.any(AppQueryRequest.class)))
                .thenReturn(databasePage);
        when(appViewAssembler.toSensitiveViewList(List.of())).thenReturn(List.of());
        when(appViewAssembler.toPublicViewList(List.of())).thenReturn(List.of());
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

    @Test
    void deniedDetailLookupMustNotAssembleSensitiveApplicationData() {
        App app = App.builder().id(7L).tenantId(11L).build();
        User actor = User.builder().id(9L).build();
        when(appPersistenceService.findActiveById(7L)).thenReturn(app);
        doThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR, "denied"))
                .when(appAccessPolicy)
                .requireViewerOrAdmin(app, actor, "无权查看该应用详情");

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.getAuthorizedDetail(7L, actor));

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
        verifyNoInteractions(appViewAssembler);
    }
}
