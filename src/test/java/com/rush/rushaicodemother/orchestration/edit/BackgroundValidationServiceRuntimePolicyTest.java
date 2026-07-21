package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import com.rush.rushaicodemother.service.devserver.DevServerValidationService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackgroundValidationServiceRuntimePolicyTest {

    @Test
    void runtimeCancellationMustPropagateAndMustNotRestartStoppedDevServer() {
        EditValidationPolicyService validationPolicyService = mock(EditValidationPolicyService.class);
        GenerationEventPublisher eventPublisher = mock(GenerationEventPublisher.class);
        EditStatePersistenceService persistenceService = mock(EditStatePersistenceService.class);
        VueProjectBuilder projectBuilder = mock(VueProjectBuilder.class);
        DevServerManager devServerManager = mock(DevServerManager.class);
        DevServerValidationService devServerValidationService = mock(DevServerValidationService.class);
        GenerationExecutionContextService executionContextService = mock(GenerationExecutionContextService.class);
        BackgroundValidationService service = new BackgroundValidationService(
                validationPolicyService,
                eventPublisher,
                persistenceService,
                projectBuilder,
                devServerManager,
                devServerValidationService,
                executionContextService
        );
        GenerationExecutionCancelledException cancellation =
                new GenerationExecutionCancelledException("user requested stop");
        when(devServerManager.isRunning(11L)).thenReturn(true);
        when(devServerManager.getPort(11L)).thenReturn(5180);
        when(projectBuilder.buildProjectWithResult(any(String.class), eq("task-1"))).thenThrow(cancellation);
        when(executionContextService.shouldStop("task-1")).thenReturn(true);

        GenerationExecutionCancelledException thrown = assertThrows(
                GenerationExecutionCancelledException.class,
                () -> service.executeValidation(
                        "task-1",
                        11L,
                        7L,
                        workspace(),
                        List.of(),
                        buildRequiredPlan(),
                        "fix the application"
                )
        );

        assertSame(cancellation, thrown);
        verify(devServerManager).stopDevServer(11L);
        verify(devServerManager, never()).startDevServer(any(), anyLong());
        verify(persistenceService, never()).recordValidationResult(anyLong(), any(), any());
    }

    private GenerationWorkspace workspace() {
        Path root = Path.of("target", "background-validation-runtime-policy").toAbsolutePath();
        return new GenerationWorkspace(
                11L,
                CodeGenTypeEnum.VUE_PROJECT,
                root,
                root,
                true,
                null,
                null,
                Set.of(),
                Set.of(".vue")
        );
    }

    private EditValidationPlan buildRequiredPlan() {
        return new EditValidationPlan(
                EditValidationPlan.ValidationLevel.BUILD_REQUIRED,
                "runtime policy regression",
                List.of(),
                false
        );
    }
}
