package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationSessionProperties;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineExecutor;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GenerationTaskSubmissionServiceTest {

    private GenerationSessionRegistry sessionRegistry;
    private GenerationExecutionContextService executionContextService;
    private GenerationPipelineExecutor pipelineExecutor;
    private GenerationTaskRuntimeLifecycleService runtimeLifecycleService;

    @BeforeEach
    void setUp() {
        sessionRegistry = new GenerationSessionRegistry(new GenerationSessionProperties());
        executionContextService = new GenerationExecutionContextService(new GenerationRuntimeProperties());
        pipelineExecutor = mock(GenerationPipelineExecutor.class);
        runtimeLifecycleService = mock(GenerationTaskRuntimeLifecycleService.class);
    }

    @Test
    void submitMustReturnBeforePipelineExecutionAndReuseOneTaskEnvelope() {
        CapturingExecutor executor = new CapturingExecutor();
        GenerationTaskSubmissionService service = service(executor, "task-submit-1");

        GenerationTaskResult result = service.submit(request(1L));

        assertEquals("task-submit-1", result.taskId());
        assertNotNull(executor.submittedTask);
        verify(pipelineExecutor, never()).execute(any());
        assertEquals("task-submit-1", sessionRegistry.get(1L).taskId());
        assertEquals(sessionRegistry.get(1L), sessionRegistry.getByTaskId("task-submit-1"));
        assertTrue(executionContextService.getByTaskId("task-submit-1").isPresent());
        verify(runtimeLifecycleService).submit(
                org.mockito.ArgumentMatchers.argThat(execution ->
                        "task-submit-1".equals(execution.taskId())),
                org.mockito.ArgumentMatchers.eq("lightweight_edit"));

        executor.submittedTask.run();

        verify(pipelineExecutor).execute(any(GenerationPipelineRequest.class));
    }

    @Test
    void durableShellMustBeCreatedBeforeExecutorCanObserveTask() {
        GenerationTaskExecutor executor = mock(GenerationTaskExecutor.class);
        GenerationTaskSubmissionService service = service(executor, "task-order");

        service.submit(request(1L));

        InOrder order = inOrder(runtimeLifecycleService, executor);
        order.verify(runtimeLifecycleService).submit(
                org.mockito.ArgumentMatchers.argThat(execution ->
                        "task-order".equals(execution.taskId())),
                org.mockito.ArgumentMatchers.eq("lightweight_edit"));
        order.verify(executor).execute(
                org.mockito.ArgumentMatchers.eq("task-order"),
                org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void activeTaskMustRejectConcurrentSubmissionForSameApplication() {
        CapturingExecutor executor = new CapturingExecutor();
        GenerationTaskSubmissionService service = service(executor, "task-concurrent");
        service.submit(request(1L));

        assertThrows(BusinessException.class, () -> service.submit(request(1L)));
    }

    @Test
    void executorRejectionMustCompensateSessionAndExecutionContext() {
        GenerationTaskExecutor rejectingExecutor = (taskId, task) -> {
            throw new IllegalStateException("executor unavailable");
        };
        GenerationTaskSubmissionService service = service(rejectingExecutor, "task-rejected");

        assertThrows(IllegalStateException.class, () -> service.submit(request(1L)));

        assertNull(sessionRegistry.get(1L));
        assertNull(sessionRegistry.getByTaskId("task-rejected"));
        assertTrue(executionContextService.getByTaskId("task-rejected").isEmpty());
        verify(runtimeLifecycleService).complete(
                "task-rejected", GenerationTaskStatus.FAILED, "submission_failed");
    }

    private GenerationTaskSubmissionService service(GenerationTaskExecutor executor, String taskId) {
        return new GenerationTaskSubmissionService(
                () -> taskId,
                executionContextService,
                sessionRegistry,
                executor,
                pipelineExecutor,
                runtimeLifecycleService
        );
    }

    private GenerationPipelineRequest request(Long appId) {
        App app = new App();
        app.setId(appId);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        User user = new User();
        user.setId(2L);
        Path root = Path.of("target/submission-test", appId.toString()).toAbsolutePath().normalize();
        GenerationWorkspace workspace = new GenerationWorkspace(
                appId, CodeGenTypeEnum.VUE_PROJECT, root, root, true, root, root, Set.of(), Set.of());
        GenerationModeDecision decision = GenerationModeDecision.of(
                GenerationMode.LIGHT_EDIT, 0.9, "test", FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD);
        return new GenerationPipelineRequest(
                new GenerationTaskRequest(app, "update title", user),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace,
                decision
        );
    }

    private static final class CapturingExecutor implements GenerationTaskExecutor {
        private Runnable submittedTask;

        @Override
        public void execute(String taskId, Runnable task) {
            this.submittedTask = task;
        }
    }
}
