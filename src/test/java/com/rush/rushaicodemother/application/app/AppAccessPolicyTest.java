package com.rush.rushaicodemother.application.app;

import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppAccessPolicyTest {

    private final AppAccessPolicy policy = new AppAccessPolicy();

    @Test
    void ownerCheckMustRejectAnonymousAndDifferentUser() {
        App app = App.builder().id(10L).userId(2L).build();

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
    void ownerOrAdministratorCheckMustAllowBothRoles() {
        App app = App.builder().id(10L).userId(2L).build();
        User owner = User.builder().id(2L).build();
        User administrator = User.builder().id(99L).userRole(UserConstant.ADMIN_ROLE).build();

        assertDoesNotThrow(() -> policy.requireOwnerOrAdmin(app, owner, "denied"));
        assertDoesNotThrow(() -> policy.requireOwnerOrAdmin(app, administrator, "denied"));
    }
}