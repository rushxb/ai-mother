package com.rush.rushaicodemother.application.app;

import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.TenantRole;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 应用访问策略。
 *
 * <p>集中维护应用所有者和管理员的授权规则，避免不同入口出现不一致或空指针。</p>
 */
@Component
@RequiredArgsConstructor
public class AppAccessPolicy {

    private final TenantAuthorizationService tenantAuthorizationService;

    /**
     * 兼容旧调用名：应用编辑能力由租户 DEVELOPER 及以上角色持有。
     */
    public App requireOwner(App app, User actor, String deniedMessage) {
        requireAuthenticated(actor);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        tenantAuthorizationService.requireRole(
                app.getTenantId(), actor.getId(), TenantRole.DEVELOPER, deniedMessage);
        return app;
    }

    /**
     * 兼容旧调用名：租户 ADMIN/OWNER 或平台管理员可以执行管理类操作。
     */
    public App requireOwnerOrAdmin(App app, User actor, String deniedMessage) {
        requireAuthenticated(actor);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        boolean administrator = UserConstant.ADMIN_ROLE.equals(actor.getUserRole());
        if (!administrator) {
            tenantAuthorizationService.requireRole(
                    app.getTenantId(), actor.getId(), TenantRole.ADMIN, deniedMessage);
        }
        return app;
    }

    /** 租户任意有效成员或平台管理员可以读取敏感应用详情。 */
    public App requireViewerOrAdmin(App app, User actor, String deniedMessage) {
        requireAuthenticated(actor);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        if (!UserConstant.ADMIN_ROLE.equals(actor.getUserRole())) {
            tenantAuthorizationService.requireRole(
                    app.getTenantId(), actor.getId(), TenantRole.VIEWER, deniedMessage);
        }
        return app;
    }

    private void requireAuthenticated(User actor) {
        if (actor == null || actor.getId() == null || actor.getId() <= 0) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        }
    }
}
