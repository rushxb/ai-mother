package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.config.CodeStorageProperties;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.builder.VueBuildCommandResult;
import com.rush.rushaicodemother.core.builder.VueBuildResult;
import com.rush.rushaicodemother.core.builder.GoProjectBuilder;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationRollbackRestoreService;
import com.rush.rushaicodemother.service.devserver.DevServerValidationResult;
import com.rush.rushaicodemother.service.devserver.DevServerValidationRequest;
import com.rush.rushaicodemother.service.devserver.DevServerValidationService;
import com.rush.rushaicodemother.service.impl.GeneratedProjectWorkspaceInspector;
import com.rush.rushaicodemother.orchestration.preview.GenerationPreviewMilestoneService;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedBackendRuntimeVerifier;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedFullStackRuntimeVerifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HeavyGenerationDiagnosticBoundaryTest {

    @Test
    void buildResultEventMustExposeOnlySanitizedDiagnostics() throws Exception {
        long appId = 930_001L;
        String taskId = "diagnostic-task-1";
        Path projectPath = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId)
                .toAbsolutePath()
                .normalize();
        FileUtil.del(projectPath.toFile());
        Files.createDirectories(projectPath);
        Files.writeString(projectPath.resolve("package.json"), "{\"scripts\":{\"build\":\"vite build\"}}",
                StandardCharsets.UTF_8);
        try {
            DevServerValidationService devServerValidationService = mock(DevServerValidationService.class);
            GenerationProjectBuildValidationService projectBuildValidationService =
                    mock(GenerationProjectBuildValidationService.class);
            VueBuildCommandResult commandResult = new VueBuildCommandResult(
                    "pnpm run build",
                    true,
                    0,
                    false,
                    "C:\\Users\\rush\\workspace\\src\\App.vue:3:1 build completed\nprovider-api-key=secret-value",
                    null
            );
            VueBuildResult vueBuildResult = new VueBuildResult(
                            true,
                            "done",
                            projectPath.toString(),
                            "构建成功，registry-token=summary-secret",
                            null,
                            commandResult
                    );
            when(projectBuildValidationService.inspect(any()))
                    .thenReturn(GeneratedProjectWorkspaceInspector.inspectVueProject(projectPath));
            when(projectBuildValidationService.validate(any(), eq(taskId)))
                    .thenReturn(ProjectBuildValidationResult.fromVue(vueBuildResult));
            when(devServerValidationService.validate(any(DevServerValidationRequest.class)))
                    .thenReturn(DevServerValidationResult.passed(taskId, appId, 5));
            GeneratedBackendRuntimeVerifier backendRuntimeVerifier =
                    mock(GeneratedBackendRuntimeVerifier.class);
            GeneratedFullStackRuntimeVerifier fullStackRuntimeVerifier =
                    mock(GeneratedFullStackRuntimeVerifier.class);
            GenerationProjectRuntimeValidationService runtimeValidationService = runtimeValidationService(
                    devServerValidationService,
                    backendRuntimeVerifier,
                    fullStackRuntimeVerifier);
            HeavyGenerationBuildValidationService service = new HeavyGenerationBuildValidationService(
                    mock(GenerationTaskLifecycleService.class),
                    mock(GenerationOrchestrationMetricsCollector.class),
                    new GenerationPerformanceMonitorService(),
                    mock(HeavyGenerationExecutionService.class),
                    mock(HeavyGenerationFailureRecoveryService.class),
                    mock(HeavyGenerationSessionCompletionService.class),
                    new GenerationWorkspaceService(new CodeStorageProperties()),
                    projectBuildValidationService,
                    runtimeValidationService,
                    mock(GenerationStageAdmissionService.class),
                    mock(GenerationPreviewMilestoneService.class)
            );
            GenerationPreparation preparation = preparation(taskId, new HashMap<>());
            GenerationSession session = managedSession(preparation, appId);

            boolean passed = service.runWithAutoRepair(
                    appId,
                    User.builder().id(7L).build(),
                    preparation,
                    session
            );

            assertTrue(passed);
            GenerationStreamEvent event = session.asFlux()
                    .filter(candidate -> GenerationStreamEvent.BUILD_RESULT.equals(candidate.getType()))
                    .blockFirst(Duration.ofSeconds(1));
            assertNotNull(event);
            assertSafeBuildDiagnostic(event.getText());
            assertSafeBuildDiagnostic(String.valueOf(event.getData().get("report")));
            String publicSummary = String.valueOf(event.getData().get("summary"));
            assertFalse(publicSummary.contains("summary-secret"));
            assertTrue(publicSummary.contains("构建成功"));
            assertFalse(String.valueOf(event.getData().get("projectPath")).contains(projectPath.toString()));
        } finally {
            FileUtil.del(projectPath.toFile());
        }
    }

    private GenerationProjectRuntimeValidationService runtimeValidationService(
            DevServerValidationService devServerValidationService,
            GeneratedBackendRuntimeVerifier backendRuntimeVerifier,
            GeneratedFullStackRuntimeVerifier fullStackRuntimeVerifier
    ) {
        VueProjectValidationAdapter vueAdapter = new VueProjectValidationAdapter(
                mock(VueProjectBuilder.class),
                devServerValidationService);
        BackendProjectValidationAdapter backendAdapter = new BackendProjectValidationAdapter(
                mock(GoProjectBuilder.class),
                backendRuntimeVerifier);
        FullStackProjectValidationAdapter fullStackAdapter = new FullStackProjectValidationAdapter(
                vueAdapter,
                backendAdapter,
                mock(GenerationExecutionContextService.class),
                fullStackRuntimeVerifier);
        return new GenerationProjectRuntimeValidationService(
                List.of(vueAdapter, backendAdapter, fullStackAdapter));
    }

    @Test
    void buildFailureBoundaryMustDefensivelySanitizeCallerProvidedTextAndData() {
        String taskId = "diagnostic-task-2";
        Map<String, GenerationArtifact> artifacts = new HashMap<>();
        artifacts.put("rollback_restore", GenerationArtifact.of(
                "rollback_restore",
                "Orchestrator",
                "回滚恢复",
                Map.of("status", "skipped", "reason", "test")
        ));
        GenerationPreparation preparation = preparation(taskId, artifacts);
        GenerationSession session = new GenerationSession(preparation);
        HeavyGenerationFailureRecoveryService service = new HeavyGenerationFailureRecoveryService(
                mock(GenerationAppStateService.class),
                mock(GenerationOrchestrationMetricsCollector.class),
                mock(GenerationRollbackRestoreService.class),
                new GenerationWorkspaceService(new CodeStorageProperties()),
                mock(com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard.class)
        );

        service.emitBuildFailure(
                930_002L,
                preparation,
                session,
                "src/App.vue:9:2 Cannot find module 'missing', provider-api-key=secret-value, "
                        + "Authorization: Bearer failure-secret"
        );

        GenerationStreamEvent event = session.asFlux().blockFirst(Duration.ofSeconds(1));
        assertNotNull(event);
        assertEquals(GenerationStreamEvent.GENERATION_ERROR, event.getType());
        assertFalse(event.getText().contains("secret-value"));
        assertFalse(event.getText().contains("failure-secret"));
        assertTrue(event.getText().contains("src/App.vue:9:2"));
        assertTrue(event.getText().contains("Cannot find module 'missing'"));
        String dataMessage = String.valueOf(event.getData().get("message"));
        assertFalse(dataMessage.contains("secret-value"));
        assertFalse(dataMessage.contains("failure-secret"));
    }

    private GenerationPreparation preparation(String taskId, Map<String, GenerationArtifact> artifacts) {
        return new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.VUE_PROJECT,
                false,
                "build",
                "build project",
                List.of(),
                artifacts,
                null,
                Map.of(),
                taskId
        );
    }

    private GenerationSession managedSession(
            GenerationPreparation preparation,
            Long appId
    ) {
        GenerationExecutionFence fence = new GenerationExecutionFence(
                preparation.taskId(), "worker-a", 1L);
        GenerationExecutionContext context = mock(GenerationExecutionContext.class);
        when(context.taskId()).thenReturn(preparation.taskId());
        when(context.appId()).thenReturn(appId);
        when(context.executionFence()).thenReturn(fence);
        return new GenerationSession(preparation, context);
    }

    private void assertSafeBuildDiagnostic(String diagnostic) {
        assertFalse(diagnostic.contains("secret-value"));
        assertFalse(diagnostic.contains("summary-secret"));
        assertFalse(diagnostic.contains("C:\\Users\\rush"));
        assertTrue(diagnostic.contains("App.vue:3:1"));
        assertTrue(diagnostic.contains("build completed"));
    }
}
