package com.rush.rushaicodemother.application.app;

import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.TenantRole;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
}
