package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.LoginUserVO;
import com.rush.rushaicodemother.security.password.PasswordHashService;
import com.rush.rushaicodemother.security.password.PasswordVerificationResult;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.user.UserPersistenceService;
import com.rush.rushaicodemother.service.user.UserViewConverter;
import com.rush.rushaicodemother.service.tenant.TenantProvisioningService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;

import static com.rush.rushaicodemother.constant.UserConstant.USER_LOGIN_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplSecurityTest {

    private PasswordHashService passwordHashService;
    private UserPersistenceService userPersistenceService;
    private UserViewConverter userViewConverter;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        passwordHashService = mock(PasswordHashService.class);
        userPersistenceService = mock(UserPersistenceService.class);
        userViewConverter = new UserViewConverter();
        userService = new UserServiceImpl(
                passwordHashService,
                mock(UserCreditService.class),
                userPersistenceService,
                userViewConverter,
                mock(TenantProvisioningService.class)
        );
    }

    @Test
    void successfulLegacyLoginMustUpgradeHashAndStoreOnlyUserIdInSession() {
        User user = User.builder()
                .id(9L)
                .userAccount("legacy-user")
                .userPassword("legacy-md5-hash")
                .build();
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(userPersistenceService.findActiveByAccount("legacy-user")).thenReturn(user);
        when(passwordHashService.verify("legacy-password", "legacy-md5-hash"))
                .thenReturn(PasswordVerificationResult.matched(true));
        when(passwordHashService.hash("legacy-password")).thenReturn("bcrypt-upgraded-hash");
        when(request.getSession(true)).thenReturn(session);

        LoginUserVO loginUser = userService.userLogin("legacy-user", "legacy-password", request);

        assertEquals(9L, loginUser.getId());
        InOrder sessionBindingOrder = inOrder(request, session);
        sessionBindingOrder.verify(request).getSession(true);
        sessionBindingOrder.verify(request).changeSessionId();
        sessionBindingOrder.verify(session).setAttribute(USER_LOGIN_STATE, 9L);
        verify(userPersistenceService).updatePasswordHash(9L, "bcrypt-upgraded-hash");
    }

    @Test
    void successfulLoginMustRotateSessionIdentifierBeforeBindingUser() {
        User user = User.builder()
                .id(15L)
                .userAccount("session-user")
                .userPassword("bcrypt-hash")
                .build();
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpSession session = request.getSession(true);
        String anonymousSessionId = session.getId();
        when(userPersistenceService.findActiveByAccount("session-user")).thenReturn(user);
        when(passwordHashService.verify("valid-password", "bcrypt-hash"))
                .thenReturn(PasswordVerificationResult.matched(false));

        userService.userLogin("session-user", "valid-password", request);

        assertNotEquals(anonymousSessionId, session.getId());
        assertEquals(15L, session.getAttribute(USER_LOGIN_STATE));
    }

    @Test
    void legacySessionObjectMustBeMigratedToUserId() {
        User legacySessionUser = User.builder().id(12L).build();
        User currentUser = User.builder().id(12L).userAccount("current-user").build();
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(USER_LOGIN_STATE)).thenReturn(legacySessionUser);
        when(userPersistenceService.findActiveById(12L)).thenReturn(currentUser);

        User resolvedUser = userService.getLoginUser(request);

        assertSame(currentUser, resolvedUser);
        verify(session).setAttribute(USER_LOGIN_STATE, 12L);
    }

    @Test
    void unexpectedPasswordEncoderFailureMustNotBeReportedAsClientInputError() {
        IllegalArgumentException failure = new IllegalArgumentException("encoder-secret=internal-value");
        when(passwordHashService.hash("valid-password")).thenThrow(failure);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> userService.userRegister("valid-user", "valid-password", "valid-password")
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertEquals("密码安全处理失败，请稍后重试", exception.getMessage());
        assertFalse(exception.getMessage().contains("internal-value"));
        assertSame(failure, exception.getCause());
        verify(userPersistenceService, never()).createUser(any());
    }
}
