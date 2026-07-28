package com.rush.rushaicodemother.ratelimiter.key;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.ratelimiter.annotation.RateLimit;
import com.rush.rushaicodemother.ratelimiter.config.RateLimiterProperties;
import com.rush.rushaicodemother.ratelimiter.ip.ClientIpResolver;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 为不同限流维度生成稳定且互不冲突的 Redis 键。
 */
@Component
public class RateLimitKeyGenerator {

    private final UserService userService;
    private final ClientIpResolver clientIpResolver;
    private final RateLimiterProperties properties;

    public RateLimitKeyGenerator(
            UserService userService,
            ClientIpResolver clientIpResolver,
            RateLimiterProperties properties
    ) {
        this.userService = userService;
        this.clientIpResolver = clientIpResolver;
        this.properties = properties;
    }

    /**
 * 根据输入生成{@code Rate}限制键生成器。
 *
 * @param method {@code method} 对应的调用参数
 * @param rateLimit {@code rateLimit} 对应的调用参数
 * @return 处理后的{@code Rate}限制键生成器文本
 */
    public String generate(Method method, RateLimit rateLimit) {
        StringBuilder key = new StringBuilder(properties.getKeyPrefix()).append(':');
        if (rateLimit.key() != null && !rateLimit.key().isBlank()) {
            key.append("scope:").append(rateLimit.key().trim()).append(':');
        }

        return switch (rateLimit.limitType()) {
            case API -> key.append("api:").append(methodSignature(method)).toString();
            case USER -> appendUserOrIpKey(key, currentRequest());
            case IP -> key.append("ip:").append(clientIpResolver.resolve(currentRequest())).toString();
        };
    }

    /** 追加用户{@code Or}{@code Ip}键。 */
    private String appendUserOrIpKey(StringBuilder key, HttpServletRequest request) {
        try {
            User loginUser = userService.getLoginUser(request);
            if (loginUser == null || loginUser.getId() == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "登录用户缺少有效 ID");
            }
            return key.append("user:").append(loginUser.getId()).toString();
        } catch (BusinessException exception) {
            if (exception.getCode() != ErrorCode.NOT_LOGIN_ERROR.getCode()) {
                throw exception;
            }
            return key.append("ip:").append(clientIpResolver.resolve(request)).toString();
        }
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (!(requestAttributes instanceof ServletRequestAttributes servletRequestAttributes)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "限流器无法获取 HTTP 请求上下文");
        }
        return servletRequestAttributes.getRequest();
    }

    /** 返回{@code method}签名。 */
    private String methodSignature(Method method) {
        if (method == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "限流器无法解析目标方法");
        }
        String parameterTypes = Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.joining(","));
        return method.getDeclaringClass().getName()
                + "#"
                + method.getName()
                + "("
                + parameterTypes
                + ")";
    }
}
