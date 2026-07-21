package com.rush.rushaicodemother.service.tenant;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.mapper.TenantMapper;
import com.rush.rushaicodemother.model.entity.Tenant;
import com.rush.rushaicodemother.model.entity.TenantMembership;
import com.rush.rushaicodemother.model.enums.TenantMembershipStatus;
import com.rush.rushaicodemother.model.enums.TenantRole;
import com.rush.rushaicodemother.model.enums.TenantType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultTenantPersistenceServiceTest {

    private TenantMapper tenantMapper;
    private DefaultTenantPersistenceService service;

    @BeforeEach
    void setUp() {
        tenantMapper = mock(TenantMapper.class);
        service = new DefaultTenantPersistenceService(tenantMapper);
    }

    @Test
    void personalTenantKeyMustBeStableAndUserScoped() {
        assertEquals("personal:42", DefaultTenantPersistenceService.personalTenantKey(42L));
    }

    @Test
    void ensurePersonalTenantMustCreateOwnerMembershipAfterTenantUpsert() {
        Tenant persistedTenant = activePersonalTenant(900L, 42L);
        TenantMembership persistedMembership = activeOwnerMembership(900L, 42L);
        when(tenantMapper.selectActivePersonalByKey("personal:42"))
                .thenReturn(persistedTenant);
        when(tenantMapper.selectActiveMembership(900L, 42L))
                .thenReturn(persistedMembership);

        Tenant result = service.ensurePersonalTenant(42L, "  Team Member  ");

        assertSame(persistedTenant, result);
        ArgumentCaptor<Tenant> tenantCaptor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantMapper).upsertPersonalTenant(tenantCaptor.capture());
        Tenant candidate = tenantCaptor.getValue();
        assertEquals("personal:42", candidate.getTenantKey());
        assertEquals(TenantType.PERSONAL.getValue(), candidate.getTenantType());
        assertEquals("Team Member", candidate.getDisplayName());
        assertEquals(42L, candidate.getOwnerUserId());

        ArgumentCaptor<TenantMembership> membershipCaptor =
                ArgumentCaptor.forClass(TenantMembership.class);
        verify(tenantMapper).upsertMembership(membershipCaptor.capture());
        TenantMembership membership = membershipCaptor.getValue();
        assertEquals(900L, membership.getTenantId());
        assertEquals(42L, membership.getUserId());
        assertEquals(TenantRole.OWNER.getValue(), membership.getRole());
        assertEquals(TenantMembershipStatus.ACTIVE.getValue(), membership.getStatus());
        assertNotNull(membership.getJoinedAt());
    }

    @Test
    void repeatedProvisioningMustReuseTheSameNaturalKeys() {
        Tenant persistedTenant = activePersonalTenant(900L, 42L);
        TenantMembership persistedMembership = activeOwnerMembership(900L, 42L);
        when(tenantMapper.selectActivePersonalByKey("personal:42"))
                .thenReturn(persistedTenant);
        when(tenantMapper.selectActiveMembership(900L, 42L))
                .thenReturn(persistedMembership);

        assertSame(persistedTenant, service.ensurePersonalTenant(42L, "first"));
        assertSame(persistedTenant, service.ensurePersonalTenant(42L, "second"));

        ArgumentCaptor<Tenant> tenantCaptor = ArgumentCaptor.forClass(Tenant.class);
        verify(tenantMapper, times(2)).upsertPersonalTenant(tenantCaptor.capture());
        List<Tenant> candidates = tenantCaptor.getAllValues();
        assertEquals("personal:42", candidates.get(0).getTenantKey());
        assertEquals(candidates.get(0).getTenantKey(), candidates.get(1).getTenantKey());

        ArgumentCaptor<TenantMembership> membershipCaptor =
                ArgumentCaptor.forClass(TenantMembership.class);
        verify(tenantMapper, times(2)).upsertMembership(membershipCaptor.capture());
        assertEquals(900L, membershipCaptor.getAllValues().get(0).getTenantId());
        assertEquals(42L, membershipCaptor.getAllValues().get(1).getUserId());
    }

    @Test
    void suspendedTenantOrMembershipMustNotBeReactivatedByProvisioning() {
        when(tenantMapper.selectActivePersonalByKey("personal:42")).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> service.ensurePersonalTenant(42L, "member"));
        verify(tenantMapper, never()).upsertMembership(org.mockito.ArgumentMatchers.any());

        Tenant persistedTenant = activePersonalTenant(900L, 42L);
        when(tenantMapper.selectActivePersonalByKey("personal:42"))
                .thenReturn(persistedTenant);
        when(tenantMapper.selectActiveMembership(900L, 42L)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> service.ensurePersonalTenant(42L, "member"));
    }

    @Test
    void mismatchedOwnerAndInvalidIdentifiersMustFailClosed() {
        when(tenantMapper.selectActivePersonalByKey("personal:42"))
                .thenReturn(activePersonalTenant(900L, 99L));

        assertThrows(BusinessException.class,
                () -> service.ensurePersonalTenant(42L, "member"));

        tenantMapper = mock(TenantMapper.class);
        service = new DefaultTenantPersistenceService(tenantMapper);
        assertThrows(IllegalArgumentException.class,
                () -> service.ensurePersonalTenant(0L, "member"));
        assertThrows(IllegalArgumentException.class,
                () -> service.findActiveById(null));
        assertThrows(IllegalArgumentException.class,
                () -> service.findActiveMembership(1L, -1L));
        verifyNoInteractions(tenantMapper);
    }

    private Tenant activePersonalTenant(Long tenantId, Long ownerUserId) {
        return Tenant.builder()
                .id(tenantId)
                .tenantKey("personal:" + ownerUserId)
                .tenantType(TenantType.PERSONAL.getValue())
                .ownerUserId(ownerUserId)
                .status("active")
                .build();
    }

    private TenantMembership activeOwnerMembership(Long tenantId, Long userId) {
        return TenantMembership.builder()
                .id(901L)
                .tenantId(tenantId)
                .userId(userId)
                .role(TenantRole.OWNER.getValue())
                .status(TenantMembershipStatus.ACTIVE.getValue())
                .build();
    }
}
