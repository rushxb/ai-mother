package com.rush.rushaicodemother.aop;

import com.rush.rushaicodemother.annotation.AuthCheck;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.UserRoleEnum;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class AuthInterceptor {

    private final UserService userService;

    public AuthInterceptor(UserService userService) {
        this.userService = userService;
    }

    /**
     * 对声明了 {@link AuthCheck} 的方法执行登录与角色校验。
     *
     * @param joinPoint 切入点
     * @param authCheck 权限校验注解
     * @return 被拦截方法的返回值
     * @throws Throwable 被拦截方法抛出的异常
     */
    @Around("@annotation(authCheck)")
    public Object doInterceptor(ProceedingJoinPoint joinPoint, AuthCheck authCheck) throws Throwable {
        HttpServletRequest request = currentRequest();
        User loginUser = userService.getLoginUser(request);
        if (loginUser == null || loginUser.getId() == null || loginUser.getId() <= 0) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }

        String requiredRoleValue = authCheck.mustRole();
        if (requiredRoleValue == null || requiredRoleValue.isBlank()) {
            return joinPoint.proceed();
        }

        UserRoleEnum requiredRole = UserRoleEnum.getEnumByValue(requiredRoleValue.trim());
        if (requiredRole == null) {
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "AuthCheck 配置了不支持的角色: " + requiredRoleValue
            );
        }

        UserRoleEnum userRoleEnum = UserRoleEnum.getEnumByValue(loginUser.getUserRole());
        if (userRoleEnum == null) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }

        if (UserRoleEnum.ADMIN.equals(requiredRole) && !UserRoleEnum.ADMIN.equals(userRoleEnum)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        return joinPoint.proceed();
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (!(requestAttributes instanceof ServletRequestAttributes servletRequestAttributes)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "当前线程不存在 HTTP 请求上下文");
        }
        return servletRequestAttributes.getRequest();
    }
}



