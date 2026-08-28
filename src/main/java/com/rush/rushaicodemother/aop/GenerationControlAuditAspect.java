package com.rush.rushaicodemother.aop;

import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlAccess;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditHandle;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditOutcome;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditService;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditSubject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 对所有 {@link GenerationControlAccess} HTTP 入口记录先开始、后终结的脱敏审计事件。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class GenerationControlAuditAspect {

    private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();

    private final GenerationControlAuditService auditService;

    @Around("@annotation(access)")
    public Object audit(ProceedingJoinPoint joinPoint, GenerationControlAccess access) throws Throwable {
        Object resourceId = resolveResourceId(access.auditResourceId(), joinPoint.getArgs());
        GenerationControlAuditHandle handle = auditService.begin(
                access.value(), access.auditResource(), resourceId, currentSubject());
        try {
            Object result = joinPoint.proceed();
            completeSafely(handle, GenerationControlAuditOutcome.SUCCESS, "OK");
            return result;
        } catch (Throwable failure) {
            completeSafely(handle, outcome(failure), resultCode(failure));
            throw failure;
        }
    }

    private Object resolveResourceId(String expression, Object[] arguments) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalStateException("生成控制审计资源表达式为空");
        }
        StandardEvaluationContext context = new StandardEvaluationContext();
        Object[] safeArguments = arguments == null ? new Object[0] : arguments;
        for (int index = 0; index < safeArguments.length; index++) {
            context.setVariable("p" + index, safeArguments[index]);
            context.setVariable("a" + index, safeArguments[index]);
        }
        return EXPRESSION_PARSER.parseExpression(expression).getValue(context);
    }

    private GenerationControlAuditSubject currentSubject() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return GenerationControlAuditSubject.anonymousHttp();
        }
        HttpServletRequest request = servletAttributes.getRequest();
        HttpSession session = request.getSession(false);
        if (session == null) {
            return GenerationControlAuditSubject.anonymousHttp();
        }
        Object loginState = session.getAttribute(UserConstant.USER_LOGIN_STATE);
        if (loginState instanceof Long userId && userId > 0) {
            return GenerationControlAuditSubject.httpUser(userId);
        }
        if (loginState instanceof User user && user.getId() != null && user.getId() > 0) {
            return GenerationControlAuditSubject.httpUser(user.getId());
        }
        return GenerationControlAuditSubject.anonymousHttp();
    }

    private GenerationControlAuditOutcome outcome(Throwable failure) {
        if (!(failure instanceof BusinessException businessFailure)) {
            return GenerationControlAuditOutcome.FAILED;
        }
        int code = businessFailure.getCode();
        if (code == 40100 || code == 40101 || code == 40300) {
            return GenerationControlAuditOutcome.DENIED;
        }
        return code >= 40000 && code < 50000
                ? GenerationControlAuditOutcome.REJECTED
                : GenerationControlAuditOutcome.FAILED;
    }

    private String resultCode(Throwable failure) {
        if (failure instanceof BusinessException businessFailure) {
            return "BUSINESS_" + businessFailure.getCode();
        }
        return "INTERNAL_ERROR";
    }

    /**
     * 终结写入失败时保留 STARTED，不把已执行操作伪造成失败，也不向日志写入资源或异常原文。
     */
    private void completeSafely(GenerationControlAuditHandle handle,
                                GenerationControlAuditOutcome outcome,
                                String resultCode) {
        try {
            auditService.complete(handle, outcome, resultCode);
        } catch (RuntimeException completionFailure) {
            log.error("生成控制审计事件未能终结，eventId: {}, failureType: {}",
                    handle.eventId(), completionFailure.getClass().getSimpleName());
        }
    }
}
