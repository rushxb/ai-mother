package com.rush.rushaicodemother.aop;

import com.rush.rushaicodemother.constant.UserConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlAccess;
import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditHandle;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditOutcome;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditResource;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditService;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditSubject;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationControlAuditAspectTest {

    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void successfulHttpControlMustRecordSessionActorTargetAndResult() throws Throwable {
        GenerationControlAuditService auditService = mock(GenerationControlAuditService.class);
        GenerationControlAuditHandle handle = new GenerationControlAuditHandle(
                "11111111-1111-1111-1111-111111111111", NOW, NOW.plusSeconds(60));
        when(auditService.begin(
                GenerationControlPermission.TASK_CANCEL,
                GenerationControlAuditResource.TASK,
                "task-1",
                GenerationControlAuditSubject.httpUser(7L))).thenReturn(handle);
        GenerationControlAuditAspect aspect = new GenerationControlAuditAspect(auditService);
        ProceedingJoinPoint joinPoint = joinPoint("cancel", new Object[]{"task-1"}, "ok");
        bindUser(7L);

        Object result = aspect.audit(joinPoint, annotation("cancel"));

        assertEquals("ok", result);
        verify(auditService).complete(handle, GenerationControlAuditOutcome.SUCCESS, "OK");
    }

    @Test
    void authorizationFailureMustStoreOnlyBoundedCodeAndRethrowOriginalFailure() throws Throwable {
        GenerationControlAuditService auditService = mock(GenerationControlAuditService.class);
        GenerationControlAuditHandle handle = new GenerationControlAuditHandle(
                "11111111-1111-1111-1111-111111111111", NOW, NOW.plusSeconds(60));
        when(auditService.begin(
                GenerationControlPermission.TASK_CANCEL,
                GenerationControlAuditResource.TASK,
                "task-1",
                GenerationControlAuditSubject.httpUser(7L))).thenReturn(handle);
        GenerationControlAuditAspect aspect = new GenerationControlAuditAspect(auditService);
        BusinessException denied = new BusinessException(
                ErrorCode.NO_AUTH_ERROR, "token=must-not-be-persisted");
        ProceedingJoinPoint joinPoint = failingJoinPoint("cancel", new Object[]{"task-1"}, denied);
        bindUser(7L);

        BusinessException actual = assertThrows(
                BusinessException.class,
                () -> aspect.audit(joinPoint, annotation("cancel")));

        assertEquals(denied, actual);
        verify(auditService).complete(
                handle, GenerationControlAuditOutcome.DENIED, "BUSINESS_40101");
    }

    private ProceedingJoinPoint joinPoint(String methodName, Object[] args, Object result) throws Throwable {
        ProceedingJoinPoint joinPoint = baseJoinPoint(methodName, args);
        when(joinPoint.proceed()).thenReturn(result);
        return joinPoint;
    }

    private ProceedingJoinPoint failingJoinPoint(
            String methodName, Object[] args, Throwable failure) throws Throwable {
        ProceedingJoinPoint joinPoint = baseJoinPoint(methodName, args);
        when(joinPoint.proceed()).thenThrow(failure);
        return joinPoint;
    }

    private ProceedingJoinPoint baseJoinPoint(String methodName, Object[] args) throws Exception {
        Method method = Fixture.class.getDeclaredMethod(methodName, String.class);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(args);
        return joinPoint;
    }

    private GenerationControlAccess annotation(String methodName) throws Exception {
        return Fixture.class.getDeclaredMethod(methodName, String.class)
                .getAnnotation(GenerationControlAccess.class);
    }

    private void bindUser(long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(UserConstant.USER_LOGIN_STATE, userId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static final class Fixture {

        @GenerationControlAccess(
                value = GenerationControlPermission.TASK_CANCEL,
                auditResource = GenerationControlAuditResource.TASK,
                auditResourceId = "#p0")
        String cancel(String taskId) {
            return taskId;
        }
    }
}
