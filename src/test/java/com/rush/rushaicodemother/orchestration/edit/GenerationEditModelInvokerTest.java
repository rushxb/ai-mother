package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.ai.AiCodeEditService;
import com.rush.rushaicodemother.ai.AiCodeEditServiceFactory;
import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationEditModelInvokerTest {

    @AfterEach
    void clearMonitorContext() {
        MonitorContextHolder.clearContext();
    }

    @Test
    void managedCallConsumesAttemptUsesTaskContextAndRestoresThreadContext() {
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();
        properties.setModelCallTimeout(Duration.ofSeconds(20));
        GenerationExecutionContextService contexts = new GenerationExecutionContextService(properties);
        GenerationExecutionContext context = contexts.start("edit-task-1", 11L, 22L);

        AiCodeEditServiceFactory factory = mock(AiCodeEditServiceFactory.class);
        AiCodeEditService model = mock(AiCodeEditService.class);
        when(factory.createAiCodeEditService(any(Duration.class))).thenReturn(model);
        when(model.editCode(eq("change it"), eq("file context")))
                .thenAnswer(invocation -> {
                    assertEquals("edit-task-1", MonitorContextHolder.getContext().getTaskId());
                    assertEquals("11", MonitorContextHolder.getContext().getAppId());
                    assertEquals("22", MonitorContextHolder.getContext().getUserId());
                    return new EditResult("done", List.of(), null);
                });

        GenerationEditModelInvoker invoker = new GenerationEditModelInvoker(
                factory, contexts, new GenerationPerformanceMonitorService(),
                new GenerationStageAdmissionProperties());

        invoker.invokeManaged("edit-task-1", "initial", "change it", "file context");

        assertEquals(1, context.used(GenerationBudgetKind.MODEL_ATTEMPT));
        assertNull(MonitorContextHolder.getContext());
        verify(factory).createAiCodeEditService(Duration.ofSeconds(20));
    }

    @Test
    void cancelledTaskCannotStartAnotherModelAttempt() {
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();
        GenerationExecutionContextService contexts = new GenerationExecutionContextService(properties);
        GenerationExecutionContext context = contexts.start("edit-task-cancelled", 11L, 22L);
        context.cancel("user_requested");
        AiCodeEditServiceFactory factory = mock(AiCodeEditServiceFactory.class);

        GenerationEditModelInvoker invoker = new GenerationEditModelInvoker(
                factory, contexts, new GenerationPerformanceMonitorService(),
                new GenerationStageAdmissionProperties());

        assertThrows(GenerationExecutionCancelledException.class,
                () -> invoker.invokeManaged("edit-task-cancelled", "retry", "change", "context"));

        assertEquals(0, context.used(GenerationBudgetKind.MODEL_ATTEMPT));
        verify(factory, never()).createAiCodeEditService(any(Duration.class));
    }

    @Test
    void repairCallIsRejectedBeforeBudgetConsumptionWhenUsefulWindowCannotFit() {
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();
        properties.setTaskTimeout(Duration.ofSeconds(5));
        properties.setModelCallTimeout(Duration.ofSeconds(4));
        GenerationExecutionContextService contexts = new GenerationExecutionContextService(properties);
        GenerationExecutionContext context = contexts.start("edit-task-short", 11L, 22L);
        AiCodeEditServiceFactory factory = mock(AiCodeEditServiceFactory.class);
        GenerationEditModelInvoker invoker = new GenerationEditModelInvoker(
                factory, contexts, new GenerationPerformanceMonitorService(),
                new GenerationStageAdmissionProperties());

        assertThrows(GenerationDeadlineExceededException.class,
                () -> invoker.invokeManaged(
                        "edit-task-short", "patch_retry", "change", "context"));

        assertEquals(0, context.used(GenerationBudgetKind.MODEL_ATTEMPT));
        verify(factory, never()).createAiCodeEditService(any(Duration.class));
    }
}
