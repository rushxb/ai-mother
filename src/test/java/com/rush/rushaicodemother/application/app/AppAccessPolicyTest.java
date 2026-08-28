package com.rush.rushaicodemother.application.app;

import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.TenantRole;
import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class AppAccessPolicyTest {

    private final TenantAuthorizationService tenantAuthorizationService =
            mock(TenantAuthorizationService.class);
    private final AppAccessPolicy policy = new AppAccessPolicy(tenantAuthorizationService);

    @Test
    void editCheckMustRejectAnonymousAndUsersWithoutTenantRole() {
        App app = App.builder().id(10L).userId(2L).tenantId(100L).build();
        doThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR, "denied"))
                .when(tenantAuthorizationService)
                .requireRole(100L, 1L, TenantRole.DEVELOPER, "denied");

        BusinessException anonymous = assertThrows(
                BusinessException.class,
                () -> policy.requireOwner(app, null, "denied")
        );
        BusinessException differentUser = assertThrows(
                BusinessException.class,
                () -> policy.requireOwner(app, User.builder().id(1L).build(), "denied")
        );

        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), anonymous.getCode());
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), differentUser.getCode());
    }

    @Test
    void developerAndPlatformAdministratorMustUseTheirRespectiveAuthorizationPaths() {
        App app = App.builder().id(10L).userId(2L).tenantId(100L).build();
        User developer = User.builder().id(2L).build();
        User platformAdministrator = User.builder()
                .id(99L)
                .userRole(UserConstant.ADMIN_ROLE)
                .build();

        assertDoesNotThrow(() -> policy.requireOwner(app, developer, "denied"));
        verify(tenantAuthorizationService)
                .requireRole(100L, 2L, TenantRole.DEVELOPER, "denied");

        assertDoesNotThrow(() -> policy.requireOwnerOrAdmin(app, platformAdministrator, "denied"));
        verifyNoMoreInteractions(tenantAuthorizationService);
    }

    @Test
    void sensitiveReadMustRequireViewerMembershipButBypassTenantLookupForPlatformAdmin() {
        App app = App.builder().id(10L).tenantId(100L).build();
        User viewer = User.builder().id(3L).build();
        User platformAdministrator = User.builder()
                .id(99L)
                .userRole(UserConstant.ADMIN_ROLE)
                .build();

        assertDoesNotThrow(() -> policy.requireViewerOrAdmin(app, viewer, "denied"));
        verify(tenantAuthorizationService)
                .requireRole(100L, 3L, TenantRole.VIEWER, "denied");

        assertDoesNotThrow(() -> policy.requireViewerOrAdmin(
                app, platformAdministrator, "denied"));
        verifyNoMoreInteractions(tenantAuthorizationService);
    }

    @Test
    void generationControlMatrixMustDistinguishViewerDeveloperAndDeleteAdministrator() {
        App app = App.builder().id(10L).tenantId(100L).build();
        User actor = User.builder().id(7L).build();

        policy.requireControlPermission(
                app, actor, GenerationControlPermission.TASK_QUERY, "query denied");
        policy.requireControlPermission(
                app, actor, GenerationControlPermission.TASK_SUBMIT, "submit denied");
        policy.requireControlPermission(
                app, actor, GenerationControlPermission.APP_DELETE, "delete denied");

        verify(tenantAuthorizationService)
                .requireRole(100L, 7L, TenantRole.VIEWER, "query denied");
        verify(tenantAuthorizationService)
                .requireRole(100L, 7L, TenantRole.DEVELOPER, "submit denied");
        verify(tenantAuthorizationService)
                .requireRole(100L, 7L, TenantRole.ADMIN, "delete denied");
    }

    @Test
    void platformAdministratorMayDeleteButMayNotSubmitWithoutTenantMembership() {
        App app = App.builder().id(10L).tenantId(100L).build();
        User platformAdministrator = User.builder()
                .id(99L)
                .userRole(UserConstant.ADMIN_ROLE)
                .build();

        policy.requireControlPermission(
                app, platformAdministrator, GenerationControlPermission.APP_DELETE, "delete denied");
        policy.requireControlPermission(
                app, platformAdministrator, GenerationControlPermission.TASK_SUBMIT, "submit denied");

        verify(tenantAuthorizationService)
                .requireRole(100L, 99L, TenantRole.DEVELOPER, "submit denied");
        verifyNoMoreInteractions(tenantAuthorizationService);
    }

    @Test
    void appPolicyMustRejectPermissionsThatDependOnTaskSubmitterFacts() {
        App app = App.builder().id(10L).tenantId(100L).build();
        User actor = User.builder().id(7L).build();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> policy.requireControlPermission(
                        app, actor, GenerationControlPermission.TOOL_APPROVAL, "approval denied")
        );

        assertEquals("任务提交人约束必须使用任务事实校验", exception.getMessage());
        verifyNoInteractions(tenantAuthorizationService);
    }
}
