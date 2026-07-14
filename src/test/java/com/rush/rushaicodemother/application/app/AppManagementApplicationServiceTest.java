package com.rush.rushaicodemother.application.app;

import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.dto.app.AppAdminUpdateRequest;
import com.rush.rushaicodemother.model.dto.app.AppUpdateRequest;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.lifecycle.AppDeletionService;
import com.rush.rushaicodemother.service.provisioning.AppProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppManagementApplicationServiceTest {

    private AppPersistenceService appPersistenceService;
    private AppProvisioningService appProvisioningService;
    private AppDeletionService appDeletionService;
    private AppManagementApplicationService service;

    @BeforeEach
    void setUp() {
        appPersistenceService = mock(AppPersistenceService.class);
        appProvisioningService = mock(AppProvisioningService.class);
        appDeletionService = mock(AppDeletionService.class);
        service = new AppManagementApplicationService(
                appPersistenceService,
                new AppAccessPolicy(),
                appProvisioningService,
                appDeletionService
        );
    }

    @Test
    void copyMustDelegateSourceIdWithoutReadingStaleApplicationState() {
        User actor = User.builder().id(1L).build();
        when(appProvisioningService.copy(21L, actor)).thenReturn(31L);

        Long copiedAppId = service.copy(21L, actor);

        assertEquals(31L, copiedAppId);
        verify(appProvisioningService).copy(21L, actor);
        verifyNoInteractions(appPersistenceService);
    }

    @Test
    void nonOwnerMustNotUpdateApplication() {
        AppUpdateRequest request = new AppUpdateRequest();
        request.setId(21L);
        request.setAppName("renamed");
        when(appPersistenceService.findActiveById(21L))
                .thenReturn(App.builder().id(21L).userId(2L).build());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.updateName(request, User.builder().id(1L).build())
        );

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
        verify(appPersistenceService, never()).updateName(any(), any(), any());
    }

    @Test
    void administratorCanDeleteApplicationThroughLifecycleService() {
        App existingApp = App.builder().id(21L).userId(2L).build();
        when(appPersistenceService.findActiveById(21L)).thenReturn(existingApp);
        User administrator = User.builder()
                .id(1L)
                .userRole(UserConstant.ADMIN_ROLE)
                .build();

        service.delete(21L, administrator);

        verify(appDeletionService).delete(21L);
        verifyNoInteractions(appProvisioningService);
    }

    @Test
    void administratorUpdateMustUseExplicitFieldWhitelist() {
        AppAdminUpdateRequest request = new AppAdminUpdateRequest();
        request.setId(21L);
        request.setAppName("  production app  ");
        request.setPriority(99);
        when(appPersistenceService.findActiveById(21L)).thenReturn(
                App.builder().id(21L).userId(2L).codeGenType("vue_project").build()
        );

        service.updateAsAdministrator(request);

        verify(appPersistenceService).updateAdministrationFields(
                eq(21L),
                eq("production app"),
                isNull(),
                eq(99),
                any(java.time.LocalDateTime.class)
        );
    }
}
