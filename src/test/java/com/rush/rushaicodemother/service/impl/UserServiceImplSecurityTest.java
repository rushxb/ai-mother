package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.mapper.UserMapper;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.LoginUserVO;
import com.rush.rushaicodemother.security.password.PasswordHashService;
import com.rush.rushaicodemother.security.password.PasswordVerificationResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static com.rush.rushaicodemother.constant.UserConstant.USER_LOGIN_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceImplSecurityTest {

    private PasswordHashService passwordHashService;
    private UserMapper userMapper;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        passwordHashService = mock(PasswordHashService.class);
        userMapper = mock(UserMapper.class);
        userService = spy(new UserServiceImpl(passwordHashService));
        ReflectionTestUtils.setField(userService, "mapper", userMapper);
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
        when(userMapper.selectOneByQuery(any())).thenReturn(user);
        when(passwordHashService.verify("legacy-password", "legacy-md5-hash"))
                .thenReturn(PasswordVerificationResult.matched(true));
        when(passwordHashService.hash("legacy-password")).thenReturn("bcrypt-upgraded-hash");
        when(request.getSession(true)).thenReturn(session);
        doReturn(true).when(userService).updateById(any(User.class));

        LoginUserVO loginUser = userService.userLogin("legacy-user", "legacy-password", request);

        assertEquals(9L, loginUser.getId());
        verify(session).setAttribute(USER_LOGIN_STATE, 9L);
        ArgumentCaptor<User> updateCaptor = ArgumentCaptor.forClass(User.class);
        verify(userService).updateById(updateCaptor.capture());
        assertEquals(9L, updateCaptor.getValue().getId());
        assertEquals("bcrypt-upgraded-hash", updateCaptor.getValue().getUserPassword());
    }

    @Test
    void legacySessionObjectMustBeMigratedToUserId() {
        User legacySessionUser = User.builder().id(12L).build();
        User currentUser = User.builder().id(12L).userAccount("current-user").build();
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(USER_LOGIN_STATE)).thenReturn(legacySessionUser);
        doReturn(currentUser).when(userService).getById(12L);

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
                () -> userService.hashPassword("valid-password")
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertEquals("密码安全处理失败，请稍后重试", exception.getMessage());
        assertFalse(exception.getMessage().contains("internal-value"));
        assertSame(failure, exception.getCause());
    }
}
