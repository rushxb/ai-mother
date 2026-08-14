package com.rush.rushaicodemother.aop;

import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.UserRoleEnum;
import com.rush.rushaicodemother.service.UserService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthInterceptorTest {

    private final UserService userService = mock(UserService.class);
    private final ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    private final AuthInterceptor interceptor = new AuthInterceptor(userService);

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldRequireLoginWhenNoRoleIsDeclared() throws Throwable {
        MockHttpServletRequest request = bindRequest();
        User loginUser = user(UserRoleEnum.USER);
        Object expectedResult = new Object();
        when(userService.getLoginUser(request)).thenReturn(loginUser);
        when(joinPoint.proceed()).thenReturn(expectedResult);

        Object result = interceptor.doInterceptor(joinPoint, annotationFor("loginRequired"));

        assertSame(expectedResult, result);
        verify(userService).getLoginUser(request);
        verify(joinPoint).proceed();
    }

    @Test
    void shouldRejectMissingLoginEvenWhenUserServiceReturnsNull() throws Throwable {
        MockHttpServletRequest request = bindRequest();
        when(userService.getLoginUser(request)).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> interceptor.doInterceptor(joinPoint, annotationFor("loginRequired"))
        );

        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), exception.getCode());
        verify(joinPoint, never()).proceed();
    }

    @Test
    void shouldAllowAdminWhenAdminRoleIsRequired() throws Throwable {
        MockHttpServletRequest request = bindRequest();
        Object expectedResult = new Object();
        when(userService.getLoginUser(request)).thenReturn(user(UserRoleEnum.ADMIN));
        when(joinPoint.proceed()).thenReturn(expectedResult);

        Object result = interceptor.doInterceptor(joinPoint, annotationFor("adminRequired"));

        assertSame(expectedResult, result);
    }

    @Test
    void shouldRejectNormalUserWhenAdminRoleIsRequired() throws Throwable {
        MockHttpServletRequest request = bindRequest();
        when(userService.getLoginUser(request)).thenReturn(user(UserRoleEnum.USER));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> interceptor.doInterceptor(joinPoint, annotationFor("adminRequired"))
        );

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
        verify(joinPoint, never()).proceed();
    }

    @Test
    void shouldRejectUnknownRequiredRoleAsConfigurationError() throws Throwable {
        MockHttpServletRequest request = bindRequest();
        when(userService.getLoginUser(request)).thenReturn(user(UserRoleEnum.ADMIN));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> interceptor.doInterceptor(joinPoint, annotationFor("unknownRole"))
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        verify(joinPoint, never()).proceed();
    }

    @Test
    void shouldFailWithControlledErrorWithoutServletRequestContext() throws Throwable {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> interceptor.doInterceptor(joinPoint, annotationFor("loginRequired"))
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        verify(userService, never()).getLoginUser(org.mockito.ArgumentMatchers.any());
        verify(joinPoint, never()).proceed();
    }

    private MockHttpServletRequest bindRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private User user(UserRoleEnum role) {
        return User.builder()
                .id(1L)
                .userRole(role.getValue())
                .build();
    }

    private AuthCheck annotationFor(String methodName) {
        try {
            Method method = AnnotatedMethods.class.getDeclaredMethod(methodName);
            return method.getAnnotation(AuthCheck.class);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class AnnotatedMethods {

        @AuthCheck
        void loginRequired() {
        }

        @AuthCheck(mustRole = "admin")
        void adminRequired() {
        }

        @AuthCheck(mustRole = "super-admin")
        void unknownRole() {
        }
    }
}
