package com.rush.rushaicodemother.application.app;

import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 应用访问策略。
 *
 * <p>集中维护应用所有者和管理员的授权规则，避免不同入口出现不一致或空指针。</p>
 */
@Component
public class AppAccessPolicy {

    /** 校验当前用户是否为应用所有者。 */
    public App requireOwner(App app, User actor, String deniedMessage) {
        requireAuthenticated(actor);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        if (!Objects.equals(app.getUserId(), actor.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, deniedMessage);
        }
        return app;
    }

    /** 校验当前用户是否为应用所有者或管理员。 */
    public App requireOwnerOrAdmin(App app, User actor, String deniedMessage) {
        requireAuthenticated(actor);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        boolean owner = Objects.equals(app.getUserId(), actor.getId());
        boolean administrator = UserConstant.ADMIN_ROLE.equals(actor.getUserRole());
        if (!owner && !administrator) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, deniedMessage);
        }
        return app;
    }

    private void requireAuthenticated(User actor) {
        if (actor == null || actor.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        }
    }
}