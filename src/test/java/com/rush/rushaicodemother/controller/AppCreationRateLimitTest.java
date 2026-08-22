package com.rush.rushaicodemother.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.application.app.AppManagementApplicationService;
import com.rush.rushaicodemother.application.app.AppQueryApplicationService;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.GlobalExceptionHandler;
import com.rush.rushaicodemother.exception.SseExceptionResponseWriter;
import com.rush.rushaicodemother.exception.UserFacingMessageResolver;
import com.rush.rushaicodemother.exception.ValidationExceptionMessageResolver;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.ratelimiter.annotation.RateLimit;
import com.rush.rushaicodemother.ratelimiter.aspect.RateLimitAspect;
import com.rush.rushaicodemother.ratelimiter.config.RateLimiterProperties;
import com.rush.rushaicodemother.ratelimiter.core.DistributedRateLimitEnforcer;
import com.rush.rushaicodemother.ratelimiter.key.RateLimitKeyGenerator;
import com.rush.rushaicodemother.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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

class AppCreationRateLimitTest {

    private static final long USER_ID = 17L;
    private static final String RATE_LIMIT_KEY = "rate_limit:scope:app:create:user:" + USER_ID;

    private AppManagementApplicationService managementService;
    private DistributedRateLimitEnforcer enforcer;
    private MockMvc mockMvc;
    private AtomicLong nowSeconds;

    @BeforeEach
    void setUp() {
        managementService = mock(AppManagementApplicationService.class);
        UserService userService = mock(UserService.class);
        User loginUser = new User();
        loginUser.setId(USER_ID);
        when(userService.getLoginUser(any())).thenReturn(loginUser);
        when(managementService.create(any(), any())).thenReturn(101L);

        enforcer = mock(DistributedRateLimitEnforcer.class);
        Map<String, Integer> requestCounts = new HashMap<>();
        Map<String, Long> windowStarts = new HashMap<>();
        nowSeconds = new AtomicLong();
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            RateLimit policy = invocation.getArgument(1);
            long now = nowSeconds.get();
            long windowStart = windowStarts.computeIfAbsent(key, ignored -> now);
            if (now - windowStart >= policy.rateInterval()) {
                windowStarts.put(key, now);
                requestCounts.put(key, 0);
            }
            int currentCount = requestCounts.merge(key, 1, Integer::sum);
            if (currentCount > policy.rate()) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUEST, policy.message());
            }
            return null;
        }).when(enforcer).enforce(anyString(), any(RateLimit.class));

        RateLimitKeyGenerator keyGenerator = new RateLimitKeyGenerator(
                userService,
                request -> "203.0.113.9",
                new RateLimiterProperties()
        );
        AppController target = new AppController(
                managementService,
                mock(AppQueryApplicationService.class),
                userService
        );
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new RateLimitAspect(keyGenerator, enforcer));

        mockMvc = MockMvcBuilders.standaloneSetup((AppController) proxyFactory.getProxy())
                .setControllerAdvice(new GlobalExceptionHandler(
                        new SseExceptionResponseWriter(new ObjectMapper()),
                        new ValidationExceptionMessageResolver(new UserFacingMessageResolver()),
                        new UserFacingMessageResolver()
                ))
                .build();
    }

    @Test
    void eleventhCreationWithinOneHourMustBeRejectedBeforeModelBackedProvisioning() throws Exception {
        for (int requestIndex = 0; requestIndex < 10; requestIndex++) {
            mockMvc.perform(createRequest())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.getCode()));
        }

        // 跨过原有的一分钟窗口后仍应受小时级成本窗口约束。
        nowSeconds.set(61);
        mockMvc.perform(createRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOO_MANY_REQUEST.getCode()));

        verify(managementService, times(10)).create(any(), any());
        verify(enforcer, times(11)).enforce(eq(RATE_LIMIT_KEY), any(RateLimit.class));
    }

    private MockHttpServletRequestBuilder createRequest() {
        return post("/app/add")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "initPrompt": "创建一个包含订单和客户管理的后台应用"
                        }
                        """);
    }
}
