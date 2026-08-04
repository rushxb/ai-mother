package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.ai.model.EditOperation;
import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.config.PatchExecutionProperties;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceFileService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.ChatHistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LightweightEditWorkspaceTransactionTest {

    @TempDir
    Path tempDir;

    @Test
    void ordinaryEditShouldRollbackAppliedPatchWhenPublicationValidationFails() throws Exception {
        String taskId = "task-validation-rollback";
        String userMessage = "修改首页标题";
        Path targetFile = tempDir.resolve("src/App.vue");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "before", StandardCharsets.UTF_8);

        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        User user = new User();
        user.setId(2L);
        GenerationTaskRequest request = new GenerationTaskRequest(app, userMessage, user);
        GenerationWorkspace workspace = new GenerationWorkspace(
                app.getId(),
                CodeGenTypeEnum.VUE_PROJECT,
                tempDir,
                tempDir,
                true,
                tempDir,
                null,
                Set.of(),
                Set.of(".vue")
        );

        GenerationEditRouteService routeService = mock(GenerationEditRouteService.class);
        LightweightEditContextAssembler contextAssembler = mock(LightweightEditContextAssembler.class);
        LightweightEditAiService aiService = mock(LightweightEditAiService.class);
        LightweightEditOperationConverter operationConverter = mock(LightweightEditOperationConverter.class);
        LightweightEditPatchExecutor patchExecutor = mock(LightweightEditPatchExecutor.class);
        LightweightRuntimeValidationService runtimeValidationService = mock(LightweightRuntimeValidationService.class);
        GenerationEventPublisher eventPublisher = mock(GenerationEventPublisher.class);
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        LightweightEditTaskLifecycleService taskLifecycleService = mock(LightweightEditTaskLifecycleService.class);
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        EditValidationPolicyService validationPolicyService = mock(EditValidationPolicyService.class);
        EditStatePersistenceService statePersistenceService = mock(EditStatePersistenceService.class);
        EditFileSnapshotService snapshotService = snapshotService();

        EditOperation editOperation = new EditOperation(
                "modify", "src/App.vue", "before", "after", null);
        EditResult editResult = new EditResult(
                "标题已修改", List.of(editOperation), new EditResult.EditValidation(true, "需要快速验证"));
        List<PatchOperation> patchOperations = List.of(
                PatchOperation.modify("src/App.vue", "after"));
        PatchApplyResult applyResult = PatchApplyResult.applied(
                app.getId(), taskId, tempDir.toString(), 1, List.of("src/App.vue"));
        EditValidationPlan validationPlan = new EditValidationPlan(
                EditValidationPlan.ValidationLevel.FAST_CHECK,
                "普通编辑发布前验证",
                List.of("src/App.vue"),
                false
        );

        when(routeService.route(app, userMessage, workspace))
                .thenReturn(GenerationEditRouteResult.lightweightEdit("单文件修改", 0.98, false));
        when(contextAssembler.assemble(workspace, userMessage))
                .thenReturn(new LightweightEditContext(
                        List.of(new EditFileCandidate(
                                "src/App.vue", "App.vue", "semantic", 100,
                                "命中首页文件", List.of("首页"))),
                        "project context",
                        true));
        when(aiService.generateManaged(taskId, userMessage, "project context"))
                .thenReturn(editResult);
        when(operationConverter.convert(editResult.operations())).thenReturn(patchOperations);
        when(validationPolicyService.isRuntimeErrorRepairRequest(userMessage)).thenReturn(false);
        when(validationPolicyService.determineValidationPlan(
                eq(patchOperations),
                eq(CodeGenTypeEnum.VUE_PROJECT),
                any(EditResult.EditValidation.class),
                eq(userMessage)))
                .thenReturn(validationPlan);
        when(runtimeValidationService.validateOnce(
                taskId, app, user, workspace, patchOperations, validationPlan, userMessage))
                .thenReturn(BackgroundValidationService.ValidationResult.failed(taskId, "快速验证失败"));
        doAnswer(invocation -> {
            Files.writeString(targetFile, "after", StandardCharsets.UTF_8);
            return new LightweightEditAttempt(editResult, patchOperations, applyResult);
        }).when(patchExecutor).applyWithRetry(
                eq(request),
                eq(app.getId()),
                eq(taskId),
                eq(tempDir),
                eq(userMessage),
                eq("project context"),
                eq(editResult),
                eq(patchOperations),
                any(EditWorkspaceTransaction.class),
                anyBoolean());

        LightweightEditService service = new LightweightEditService(
                routeService,
                contextAssembler,
                aiService,
                operationConverter,
                patchExecutor,
                runtimeValidationService,
                eventPublisher,
                workspaceService,
                taskLifecycleService,
                chatHistoryService,
                validationPolicyService,
                statePersistenceService,
                snapshotService
        );

        LightweightEditResult result = service.execute(taskId, request, workspace);

        assertEquals("failed", result.validationResult());
        assertEquals("before", Files.readString(targetFile, StandardCharsets.UTF_8));
        verify(patchExecutor).refreshIndex(tempDir, List.of("src/App.vue"));
        verify(eventPublisher).publishSafely(
                eq(request),
                eq(GenerationEventType.EDIT_ROLLBACK),
                eq("发布验证未通过，已回滚本次编辑"),
                any());
    }

    private EditFileSnapshotService snapshotService() {
        PatchExecutionProperties properties = new PatchExecutionProperties();
        return new EditFileSnapshotService(
                new PatchWorkspaceFileService(properties),
                properties,
                mock(GenerationTaskFenceGuard.class));
    }
}