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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        AtomicReference<Runnable> beforeModelTurn = new AtomicReference<>();
        when(factory.createExecutionAiCodeEditService(
                any(Duration.class), any(Runnable.class), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    beforeModelTurn.set(invocation.getArgument(1));
                    return model;
                });
        when(model.editCode(eq("change it"), eq("file context")))
                .thenAnswer(invocation -> {
                    beforeModelTurn.get().run();
                    assertEquals("edit-task-1", MonitorContextHolder.getContext().getTaskId());
                    assertEquals("11", MonitorContextHolder.getContext().getAppId());
                    assertEquals("22", MonitorContextHolder.getContext().getUserId());
                    return new EditResult("done", List.of(), null);
                });

        GenerationPerformanceMonitorService performanceMonitorService = new GenerationPerformanceMonitorService();
        performanceMonitorService.startTask("edit-task-1", 11L, 22L, "agent_edit", "vue_project");
        GenerationEditModelInvoker invoker = new GenerationEditModelInvoker(
                factory, contexts, performanceMonitorService,
                new GenerationStageAdmissionProperties());

        invoker.invokeManaged("edit-task-1", "initial", "change it", "file context");

        assertEquals(1, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(1, context.used(GenerationBudgetKind.MODEL_TURN));
        assertEquals(0, context.used(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT));
        assertNull(MonitorContextHolder.getContext());
        verify(factory).createExecutionAiCodeEditService(
                eq(Duration.ofSeconds(20)), any(Runnable.class), any(Runnable.class));
        assertTrue(performanceMonitorService.getSummary(10).getRecentTasks().getFirst()
                .getFirstTokenLatencyMs() > 0);
        assertTrue(performanceMonitorService.getSummary(10).getRecentTasks().getFirst().getSpans().stream()
                .anyMatch(span -> "model_time_to_first_signal".equals(span.getStage())));
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
                () -> invoker.invokeManagedRepair(
                        "edit-task-cancelled", "retry", "change", "context"));

        assertEquals(0, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        verify(factory, never()).createExecutionAiCodeEditService(
                any(Duration.class), any(Runnable.class), any(Runnable.class));
    }

    @Test
    void repairCallIsRejectedBeforeBudgetConsumptionWhenUsefulWindowCannotFit() {
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();
        properties.setTaskTimeout(Duration.ofSeconds(5));
        properties.setModelCallTimeout(Duration.ofSeconds(4));
        properties.setFirstPreviewCompletionReserve(Duration.ofMillis(500));
        GenerationExecutionContextService contexts = new GenerationExecutionContextService(properties);
        GenerationExecutionContext context = contexts.start("edit-task-short", 11L, 22L);
        AiCodeEditServiceFactory factory = mock(AiCodeEditServiceFactory.class);
        GenerationEditModelInvoker invoker = new GenerationEditModelInvoker(
                factory, contexts, new GenerationPerformanceMonitorService(),
                new GenerationStageAdmissionProperties());

        assertThrows(GenerationDeadlineExceededException.class,
                () -> invoker.invokeManagedRepair(
                        "edit-task-short", "patch_retry", "change", "context"));

        assertEquals(0, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(0, context.used(GenerationBudgetKind.REPAIR_ROUND));
        verify(factory, never()).createExecutionAiCodeEditService(
                any(Duration.class), any(Runnable.class), any(Runnable.class));
    }

    @Test
    void optionalEditRepairsMustShareTheDeclaredTaskRepairBudget() {
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();
        GenerationExecutionContextService contexts = new GenerationExecutionContextService(properties);
        GenerationExecutionContext context = contexts.start("edit-task-repair-budget", 11L, 22L);
        AiCodeEditServiceFactory factory = mock(AiCodeEditServiceFactory.class);
        AiCodeEditService model = mock(AiCodeEditService.class);
        when(factory.createExecutionAiCodeEditService(
                any(Duration.class), any(Runnable.class), any(Runnable.class)))
                .thenReturn(model);
        when(model.editCode(any(String.class), any(String.class)))
                .thenReturn(new EditResult("done", List.of(), null));
        GenerationEditModelInvoker invoker = new GenerationEditModelInvoker(
                factory, contexts, new GenerationPerformanceMonitorService(),
                new GenerationStageAdmissionProperties());

        invoker.invokeManaged("edit-task-repair-budget", "initial", "change", "context");
        EditResult firstRepair = invoker.invokeManagedRepair(
                "edit-task-repair-budget", "patch_retry", "change", "context");
        EditResult exhaustedRepair = invoker.invokeManagedRepair(
                "edit-task-repair-budget", "validation_retry", "change", "context");

        assertNotNull(firstRepair);
        assertNull(exhaustedRepair);
        assertEquals(2, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(1, context.used(GenerationBudgetKind.REPAIR_ROUND));
        verify(factory, times(2)).createExecutionAiCodeEditService(
                any(Duration.class), any(Runnable.class), any(Runnable.class));
    }
}
