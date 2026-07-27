package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LightweightRuntimeValidationServiceTest {

    @Test
    void unavailableOptionalRepairMustStopTheValidationRetryLoop() {
        BackgroundValidationService validationService = mock(BackgroundValidationService.class);
        LightweightEditContextAssembler contextAssembler = mock(LightweightEditContextAssembler.class);
        LightweightEditAiService aiService = mock(LightweightEditAiService.class);
        when(validationService.executeValidation(
                anyString(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(BackgroundValidationService.ValidationResult.failed(
                        "light-repair-budget", "构建验证失败"));
        when(contextAssembler.rebuildAfterValidationFailure(any(), anyString(), any(), anyString()))
                .thenReturn("retry-context");
        when(aiService.retryAfterValidationFailureManaged(
                eq("light-repair-budget"), anyString(), anyString(), any()))
                .thenReturn(null);
        LightweightRuntimeValidationService service = new LightweightRuntimeValidationService(
                validationService,
                mock(EditValidationPolicyService.class),
                contextAssembler,
                aiService,
                mock(LightweightEditOperationConverter.class),
                mock(LightweightEditPatchExecutor.class),
                mock(EditFileSnapshotService.class),
                mock(GenerationEventPublisher.class)
        );
        App app = new App();
        app.setId(11L);
        User user = new User();
        user.setId(22L);
        GenerationTaskRequest request = new GenerationTaskRequest(app, "修复运行时错误", user);
        Path root = Path.of("target/light-runtime-validation-test").toAbsolutePath().normalize();
        GenerationWorkspace workspace = new GenerationWorkspace(
                11L, CodeGenTypeEnum.VUE_PROJECT, root, root, true,
                root, root, Set.of(), Set.of());
        EditValidationPlan plan = new EditValidationPlan(
                EditValidationPlan.ValidationLevel.BUILD_REQUIRED,
                "runtime_error_repair",
                List.of("src/App.vue"),
                true
        );
        List<PatchOperation> operations = List.of(PatchOperation.modify("src/App.vue", "content"));

        LightweightRuntimeValidationOutcome outcome = service.validateWithRetries(
                request,
                app,
                user,
                "light-repair-budget",
                workspace,
                request.message(),
                "project-context",
                new EditResult("initial", List.of(), null),
                operations,
                PatchApplyResult.applied(11L, "light-repair-budget", root.toString(), 1,
                        List.of("src/App.vue")),
                plan,
                null,
                true
        );

        assertFalse(outcome.success());
        verify(aiService, times(1)).retryAfterValidationFailureManaged(
                eq("light-repair-budget"), anyString(), anyString(), any());
    }
}
