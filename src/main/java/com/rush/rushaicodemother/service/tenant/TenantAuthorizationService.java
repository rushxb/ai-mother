package com.rush.rushaicodemother.service.tenant;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.Tenant;
import com.rush.rushaicodemother.model.entity.TenantMembership;
import com.rush.rushaicodemother.model.enums.TenantMembershipStatus;
import com.rush.rushaicodemother.model.enums.TenantRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TenantAuthorizationService {

    private final TenantPersistenceService tenantPersistenceService;

    public TenantMembership requireRole(Long tenantId,
                                        Long userId,
                                        TenantRole requiredRole,
                                        String deniedMessage) {
        if (tenantId == null || tenantId <= 0 || userId == null || userId <= 0 || requiredRole == null) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, deniedMessage);
        }
        Tenant tenant = tenantPersistenceService.findActiveById(tenantId);
        TenantMembership membership = tenantPersistenceService.findActiveMembership(tenantId, userId);
        if (tenant == null
                || membership == null
                || !tenantId.equals(tenant.getId())
                || !tenantId.equals(membership.getTenantId())
                || !userId.equals(membership.getUserId())
                || !"active".equalsIgnoreCase(tenant.getStatus())
                || !TenantMembershipStatus.ACTIVE.getValue().equalsIgnoreCase(membership.getStatus())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, deniedMessage);
        }
        TenantRole actualRole;
        try {
            actualRole = TenantRole.fromValue(membership.getRole());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, deniedMessage);
        }
        if (!actualRole.includes(requiredRole)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, deniedMessage);
        }
        return membership;
    }
}
