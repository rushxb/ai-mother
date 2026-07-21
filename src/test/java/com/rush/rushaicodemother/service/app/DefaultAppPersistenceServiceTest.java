package com.rush.rushaicodemother.service.app;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.AppMapper;
import com.rush.rushaicodemother.model.dto.app.AppQueryRequest;
import com.rush.rushaicodemother.model.entity.App;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAppPersistenceServiceTest {

    private AppMapper appMapper;
    private DefaultAppPersistenceService service;

    @BeforeEach
    void setUp() {
        appMapper = mock(AppMapper.class);
        service = new DefaultAppPersistenceService(appMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingSortFieldMustUseDefaultSortWithoutThrowing() {
        AppQueryRequest request = new AppQueryRequest();
        Page<App> databasePage = Page.of(1, 10);
        when(appMapper.paginate(any(Page.class), any(QueryWrapper.class))).thenReturn(databasePage);

        assertEquals(databasePage, service.pageActiveApps(request));
    }

    @Test
    void preparedCreateMustPersistOnlyWhitelistedFieldsAndReturnGeneratedId() {
        when(appMapper.insertPreparedApp(any(App.class))).thenAnswer(invocation -> {
            App entity = invocation.getArgument(0);
            entity.setId(88L);
            return 1;
        });

        long appId = service.createPrepared(new AppPersistenceService.NewApp(
                "benchmark", "build a dashboard", "vue_project", 0, 9L, 700L));

        assertEquals(88L, appId);
        verify(appMapper).insertPreparedApp(any(App.class));
    }

    @Test
    void nameUpdateMustWhitelistFieldsAndRequireOneAffectedRow() {
        LocalDateTime editTime = LocalDateTime.of(2026, 7, 13, 19, 0);
        when(appMapper.updateActiveName(21L, "production app", editTime)).thenReturn(1);

        service.updateName(21L, "production app", editTime);

        verify(appMapper).updateActiveName(21L, "production app", editTime);
    }

    @Test
    void updateMustFailWhenTargetRowWasNotChanged() {
        when(appMapper.updateActiveDevServerPort(21L, 5180)).thenReturn(0);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateDevServerPort(21L, 5180));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        assertEquals("保存 Dev Server 端口失败", exception.getMessage());
    }

    @Test
    void administrationUpdateMustRejectEmptyPatchBeforeDatabaseAccess() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.updateAdministrationFields(
                        21L, null, null, null, LocalDateTime.now()));

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        verify(appMapper, never()).updateActiveAdministrationFields(
                eq(21L), any(), any(), any(), any());
    }
}
