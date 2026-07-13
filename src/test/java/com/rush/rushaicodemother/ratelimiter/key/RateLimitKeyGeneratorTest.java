package com.rush.rushaicodemother.ratelimiter.key;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.ratelimiter.annotation.RateLimit;
import com.rush.rushaicodemother.ratelimiter.config.RateLimiterProperties;
import com.rush.rushaicodemother.ratelimiter.enums.RateLimitType;
import com.rush.rushaicodemother.ratelimiter.ip.ClientIpResolver;
import com.rush.rushaicodemother.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitKeyGeneratorTest {

    private final UserService userService = mock(UserService.class);
    private final ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);
    private final RateLimitKeyGenerator keyGenerator =
            new RateLimitKeyGenerator(userService, clientIpResolver, new RateLimiterProperties());

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldGenerateStableApiKeyWithoutRequestContext() throws Exception {
        Method method = LimitedMethods.class.getDeclaredMethod("apiMethod", String.class);

        String key = keyGenerator.generate(method, method.getAnnotation(RateLimit.class));

        assertEquals(
                "rate_limit:scope:catalog:api:"
                        + LimitedMethods.class.getName()
                        + "#apiMethod(java.lang.String)",
                key
        );
        verify(userService, never()).getLoginUser(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldGenerateUserKeyForAuthenticatedRequest() throws Exception {
        MockHttpServletRequest request = bindRequest();
        when(userService.getLoginUser(request)).thenReturn(User.builder().id(42L).build());
        Method method = LimitedMethods.class.getDeclaredMethod("userMethod");

        String key = keyGenerator.generate(method, method.getAnnotation(RateLimit.class));

        assertEquals("rate_limit:user:42", key);
    }

    @Test
    void shouldFallBackToIpOnlyWhenUserIsNotLoggedIn() throws Exception {
        MockHttpServletRequest request = bindRequest();
        when(userService.getLoginUser(request)).thenThrow(new BusinessException(ErrorCode.NOT_LOGIN_ERROR));
        when(clientIpResolver.resolve(request)).thenReturn("203.0.113.7");
        Method method = LimitedMethods.class.getDeclaredMethod("userMethod");

        String key = keyGenerator.generate(method, method.getAnnotation(RateLimit.class));

        assertEquals("rate_limit:ip:203.0.113.7", key);
    }

    @Test
    void shouldNotHideOtherAuthenticationFailures() throws Exception {
        MockHttpServletRequest request = bindRequest();
        BusinessException expected = new BusinessException(ErrorCode.SYSTEM_ERROR, "database unavailable");
        when(userService.getLoginUser(request)).thenThrow(expected);
        Method method = LimitedMethods.class.getDeclaredMethod("userMethod");

        BusinessException actual = assertThrows(
                BusinessException.class,
                () -> keyGenerator.generate(method, method.getAnnotation(RateLimit.class))
        );

        assertEquals(expected, actual);
        verify(clientIpResolver, never()).resolve(request);
    }

    @Test
    void shouldFailClosedWithoutRequestContextForIpPolicy() throws Exception {
        Method method = LimitedMethods.class.getDeclaredMethod("ipMethod");

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> keyGenerator.generate(method, method.getAnnotation(RateLimit.class))
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
    }

    private MockHttpServletRequest bindRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private static final class LimitedMethods {

        @RateLimit(key = "catalog", limitType = RateLimitType.API)
        void apiMethod(String value) {
        }

        @RateLimit(limitType = RateLimitType.USER)
        void userMethod() {
        }

        @RateLimit(limitType = RateLimitType.IP)
        void ipMethod() {
        }
    }
}
