package com.rush.rushaicodemother.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.GlobalExceptionHandler;
import com.rush.rushaicodemother.exception.SseExceptionResponseWriter;
import com.rush.rushaicodemother.exception.UserFacingMessageResolver;
import com.rush.rushaicodemother.exception.ValidationExceptionMessageResolver;
import com.rush.rushaicodemother.model.vo.LoginUserVO;
import com.rush.rushaicodemother.ratelimiter.annotation.RateLimit;
import com.rush.rushaicodemother.ratelimiter.aspect.RateLimitAspect;
import com.rush.rushaicodemother.ratelimiter.config.RateLimiterProperties;
import com.rush.rushaicodemother.ratelimiter.core.DistributedRateLimitEnforcer;
import com.rush.rushaicodemother.ratelimiter.key.RateLimitKeyGenerator;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.user.UserDirectoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserAuthenticationRateLimitTest {

    private static final String CLIENT_IP = "203.0.113.7";
    private static final String REGISTER_LIMIT_KEY =
            "rate_limit:scope:authentication:register:ip:" + CLIENT_IP;
    private static final String LOGIN_LIMIT_KEY =
            "rate_limit:scope:authentication:login:ip:" + CLIENT_IP;

    private UserService userService;
    private DistributedRateLimitEnforcer enforcer;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        enforcer = mock(DistributedRateLimitEnforcer.class);
        Map<String, Integer> requestCounts = new HashMap<>();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            RateLimit policy = invocation.getArgument(1);
            int currentCount = requestCounts.merge(key, 1, Integer::sum);
            if (currentCount > policy.rate()) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, policy.message());
            }
            return null;
        }).when(enforcer).enforce(anyString(), any(RateLimit.class));

        UserController target = new UserController(
                userService,
                mock(UserCreditService.class),
                mock(UserDirectoryService.class)
        );
        RateLimitKeyGenerator keyGenerator = new RateLimitKeyGenerator(
                userService,
                request -> CLIENT_IP,
                new RateLimiterProperties()
        );
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new RateLimitAspect(keyGenerator, enforcer));
        UserController controllerProxy = proxyFactory.getProxy();

        mockMvc = MockMvcBuilders.standaloneSetup(controllerProxy)
                .setControllerAdvice(new GlobalExceptionHandler(
                        new SseExceptionResponseWriter(new ObjectMapper()),
                        new ValidationExceptionMessageResolver(new UserFacingMessageResolver()),
                        new UserFacingMessageResolver()
                ))
                .build();
    }

    @Test
    void fourthRegistrationFromSameIpMustBeRejectedBeforePasswordHashingAndPersistence() throws Exception {
        when(userService.userRegister("abuse-user", "password-123", "password-123"))
                .thenReturn(7L);

        for (int requestIndex = 0; requestIndex < 3; requestIndex++) {
            mockMvc.perform(registerRequest())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.getCode()));
        }

        mockMvc.perform(registerRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOO_MANY_REQUEST.getCode()));

        verify(userService, times(3))
                .userRegister("abuse-user", "password-123", "password-123");
        verify(enforcer, times(4))
                .enforce(eq(REGISTER_LIMIT_KEY), any(RateLimit.class));
    }

    @Test
    void eleventhLoginFromSameIpMustBeRejectedBeforePasswordVerification() throws Exception {
        LoginUserVO loginUser = new LoginUserVO();
        loginUser.setId(7L);
        when(userService.userLogin(
                eq("abuse-user"),
                eq("password-123"),
                any()))
                .thenReturn(loginUser);

        for (int requestIndex = 0; requestIndex < 10; requestIndex++) {
            mockMvc.perform(loginRequest())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.getCode()));
        }

        mockMvc.perform(loginRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOO_MANY_REQUEST.getCode()));

        verify(userService, times(10)).userLogin(
                eq("abuse-user"),
                eq("password-123"),
                any());
        verify(enforcer, times(11))
                .enforce(eq(LOGIN_LIMIT_KEY), any(RateLimit.class));
    }

    private MockHttpServletRequestBuilder registerRequest() {
        return post("/user/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "userAccount": "abuse-user",
                          "userPassword": "password-123",
                          "checkPassword": "password-123"
                        }
                        """);
    }

    private MockHttpServletRequestBuilder loginRequest() {
        return post("/user/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "userAccount": "abuse-user",
                          "userPassword": "password-123"
                        }
                        """);
    }
}
