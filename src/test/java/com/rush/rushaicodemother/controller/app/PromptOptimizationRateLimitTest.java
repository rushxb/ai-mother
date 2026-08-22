package com.rush.rushaicodemother.controller.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.controller.support.GenerationSseEventMapper;
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
import com.rush.rushaicodemother.service.AppService;
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

class PromptOptimizationRateLimitTest {

    private static final long USER_ID = 23L;
    private static final String RATE_LIMIT_KEY =
            "rate_limit:scope:prompt:optimize:user:" + USER_ID;

    private AppService appService;
    private DistributedRateLimitEnforcer enforcer;
    private AtomicLong nowSeconds;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        appService = mock(AppService.class);
        UserService userService = mock(UserService.class);
        User loginUser = new User();
        loginUser.setId(USER_ID);
        when(userService.getLoginUser(any())).thenReturn(loginUser);
        when(appService.optimizePrompt(anyString(), any())).thenReturn("优化后的项目需求");

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
                request -> "203.0.113.10",
                new RateLimiterProperties()
        );
        AppGenerationController target = new AppGenerationController(
                appService,
                userService,
                mock(GenerationSseEventMapper.class)
        );
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new RateLimitAspect(keyGenerator, enforcer));

        mockMvc = MockMvcBuilders.standaloneSetup((AppGenerationController) proxyFactory.getProxy())
                .setControllerAdvice(new GlobalExceptionHandler(
                        new SseExceptionResponseWriter(new ObjectMapper()),
                        new ValidationExceptionMessageResolver(new UserFacingMessageResolver()),
                        new UserFacingMessageResolver()
                ))
                .build();
    }

    @Test
    void thirtyFirstOptimizationWithinOneHourMustBeRejectedBeforeModelInvocation() throws Exception {
        for (int requestIndex = 0; requestIndex < 30; requestIndex++) {
            mockMvc.perform(optimizeRequest())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ErrorCode.SUCCESS.getCode()));
        }

        // 一分钟后仍在小时级免费工具成本窗口内。
        nowSeconds.set(61);
        mockMvc.perform(optimizeRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOO_MANY_REQUEST.getCode()));

        verify(appService, times(30)).optimizePrompt(anyString(), any());
        verify(enforcer, times(31)).enforce(eq(RATE_LIMIT_KEY), any(RateLimit.class));
    }

    private MockHttpServletRequestBuilder optimizeRequest() {
        return post("/app/optimize/prompt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "prompt": "生成一个订单管理项目"
                        }
                        """);
    }
}
