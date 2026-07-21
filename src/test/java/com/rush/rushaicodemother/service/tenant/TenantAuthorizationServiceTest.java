package com.rush.rushaicodemother.service.tenant;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.Tenant;
import com.rush.rushaicodemother.model.entity.TenantMembership;
import com.rush.rushaicodemother.model.enums.TenantMembershipStatus;
import com.rush.rushaicodemother.model.enums.TenantRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantAuthorizationServiceTest {

    private TenantPersistenceService tenantPersistenceService;
    private TenantAuthorizationService service;

    @BeforeEach
    void setUp() {
        tenantPersistenceService = mock(TenantPersistenceService.class);
        service = new TenantAuthorizationService(tenantPersistenceService);
        when(tenantPersistenceService.findActiveById(100L)).thenReturn(activeTenant());
    }

    @Test
    void viewerMustNotReceiveDeveloperPermission() {
        when(tenantPersistenceService.findActiveMembership(100L, 7L))
                .thenReturn(activeMembership(TenantRole.VIEWER, 7L));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.requireRole(100L, 7L, TenantRole.DEVELOPER, "denied")
        );

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
    }

    @Test
    void developerCanEditAndAdministratorCanManage() {
        TenantMembership developer = activeMembership(TenantRole.DEVELOPER, 7L);
        when(tenantPersistenceService.findActiveMembership(100L, 7L))
                .thenReturn(developer);
        assertSame(developer,
                service.requireRole(100L, 7L, TenantRole.DEVELOPER, "denied"));

        TenantMembership administrator = activeMembership(TenantRole.ADMIN, 8L);
        when(tenantPersistenceService.findActiveMembership(100L, 8L))
                .thenReturn(administrator);
        assertSame(administrator,
                service.requireRole(100L, 8L, TenantRole.ADMIN, "denied"));
    }

    @Test
    void missingOrSuspendedMembershipMustBeRejected() {
        when(tenantPersistenceService.findActiveMembership(100L, 7L))
                .thenReturn(null);
        assertThrows(BusinessException.class,
                () -> service.requireRole(100L, 7L, TenantRole.VIEWER, "denied"));

        TenantMembership suspended = activeMembership(TenantRole.OWNER, 8L);
        suspended.setStatus(TenantMembershipStatus.SUSPENDED.getValue());
        when(tenantPersistenceService.findActiveMembership(100L, 8L))
                .thenReturn(suspended);
        assertThrows(BusinessException.class,
                () -> service.requireRole(100L, 8L, TenantRole.VIEWER, "denied"));
    }

    @Test
    void inactiveTenantAndMalformedRoleMustBeRejected() {
        Tenant inactiveTenant = activeTenant();
        inactiveTenant.setStatus("suspended");
        when(tenantPersistenceService.findActiveById(100L)).thenReturn(inactiveTenant);
        when(tenantPersistenceService.findActiveMembership(100L, 7L))
                .thenReturn(activeMembership(TenantRole.OWNER, 7L));
        assertThrows(BusinessException.class,
                () -> service.requireRole(100L, 7L, TenantRole.VIEWER, "denied"));

        when(tenantPersistenceService.findActiveById(100L)).thenReturn(activeTenant());
        TenantMembership malformed = activeMembership(TenantRole.OWNER, 8L);
        malformed.setRole("root");
        when(tenantPersistenceService.findActiveMembership(100L, 8L))
                .thenReturn(malformed);
        assertThrows(BusinessException.class,
                () -> service.requireRole(100L, 8L, TenantRole.VIEWER, "denied"));
    }

    @Test
    void invalidIdentityOrRequiredRoleMustFailBeforePersistenceAccess() {
        tenantPersistenceService = mock(TenantPersistenceService.class);
        service = new TenantAuthorizationService(tenantPersistenceService);

        assertThrows(BusinessException.class,
                () -> service.requireRole(null, 7L, TenantRole.VIEWER, "denied"));
        assertThrows(BusinessException.class,
                () -> service.requireRole(100L, 0L, TenantRole.VIEWER, "denied"));
        assertThrows(BusinessException.class,
                () -> service.requireRole(100L, 7L, null, "denied"));
        verifyNoInteractions(tenantPersistenceService);
    }

    private Tenant activeTenant() {
        return Tenant.builder()
                .id(100L)
                .status("active")
                .build();
    }

    private TenantMembership activeMembership(TenantRole role, Long userId) {
        return TenantMembership.builder()
                .tenantId(100L)
                .userId(userId)
                .role(role.getValue())
                .status(TenantMembershipStatus.ACTIVE.getValue())
                .build();
    }
}
