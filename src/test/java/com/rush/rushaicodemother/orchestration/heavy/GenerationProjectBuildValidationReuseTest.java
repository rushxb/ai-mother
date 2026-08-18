package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.core.builder.GoBuildCommandService;
import com.rush.rushaicodemother.core.builder.GoBuildResultRegistry;
import com.rush.rushaicodemother.core.builder.GoProjectBuilder;
import com.rush.rushaicodemother.core.builder.GoProjectSnapshotService;
import com.rush.rushaicodemother.core.builder.VueBuildResult;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.infrastructure.process.GoProjectCommandExecutor;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandResult;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedBackendRuntimeVerifier;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedFullStackRuntimeVerifier;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.service.devserver.DevServerValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationProjectBuildValidationReuseTest {

    @TempDir
    Path root;

    @Test
    void frontendOnlyRepairMustReuseStableBackendResultWithinSameTask() throws Exception {
        GenerationWorkspace workspace = fullStackWorkspace();
        Files.writeString(workspace.backendRootPath().resolve("go.mod"), "module example");
        Files.writeString(workspace.backendRootPath().resolve("go.sum"), "");
        Files.writeString(workspace.backendRootPath().resolve("main.go"), "package main\nfunc main() {}");

        GenerationExecutionContextService contextService = mock(GenerationExecutionContextService.class);
        GoProjectCommandExecutor commandExecutor = mock(GoProjectCommandExecutor.class);
        GenerationPerformanceMonitorService monitorService = mock(GenerationPerformanceMonitorService.class);
        GenerationPerformanceMonitorService.SpanTimer span = mock(GenerationPerformanceMonitorService.SpanTimer.class);
        when(monitorService.startSpan(eq("task-repair"), eq("go_test"), any())).thenReturn(span);
        when(commandExecutor.executeTests(
                eq(workspace.backendRootPath()),
                eq("task-repair"),
                eq("go_test:" + workspace.backendRootPath())
        )).thenReturn(successfulCommand());
        GoProjectBuilder goBuilder = new GoProjectBuilder(
                new GoBuildCommandService(commandExecutor, monitorService),
                new GoProjectSnapshotService(new WorkspaceFileSystemProperties()),
                new GoBuildResultRegistry(new ProjectCommandProperties()),
                contextService
        );

        VueProjectBuilder vueBuilder = mock(VueProjectBuilder.class);
        when(vueBuilder.buildProjectWithResult(
                eq(workspace.frontendRootPath().toString()),
                eq("task-repair"),
                any()
        )).thenAnswer(invocation -> {
            com.rush.rushaicodemother.core.builder.BuildExecutionBudgetReservation reservation =
                    invocation.getArgument(2);
            reservation.reserve();
            return new VueBuildResult(
                    true,
                    "done",
                    workspace.frontendRootPath().toString(),
                    "Vue 项目构建成功",
                    null,
                    null
            );
        });
        VueProjectValidationAdapter frontendAdapter = new VueProjectValidationAdapter(
                vueBuilder,
                mock(DevServerValidationService.class));
        BackendProjectValidationAdapter backendAdapter = new BackendProjectValidationAdapter(
                goBuilder,
                mock(GeneratedBackendRuntimeVerifier.class));
        GenerationProjectBuildValidationService service = new GenerationProjectBuildValidationService(
                List.of(
                        frontendAdapter,
                        backendAdapter,
                        new FullStackProjectValidationAdapter(
                                frontendAdapter,
                                backendAdapter,
                                contextService,
                                mock(GeneratedFullStackRuntimeVerifier.class))
                ),
                contextService
        );

        ProjectBuildValidationResult first = service.validate(
                workspace,
                "task-repair"
        );
        Files.writeString(
                workspace.frontendRootPath().resolve("src/App.vue"),
                "<template>修复后的前端</template>"
        );
        ProjectBuildValidationResult second = service.validate(
                workspace,
                "task-repair"
        );

        assertTrue(first.success());
        assertTrue(second.success());
        assertTrue(second.report().contains("复用本任务内通过的构建测试"));
        verify(commandExecutor).executeTests(
                eq(workspace.backendRootPath()),
                eq("task-repair"),
                eq("go_test:" + workspace.backendRootPath())
        );
        verify(vueBuilder, times(2)).buildProjectWithResult(
                eq(workspace.frontendRootPath().toString()),
                eq("task-repair"),
                any()
        );
        verify(contextService, times(2))
                .consumeIfPresent("task-repair", GenerationBudgetKind.BUILD_EXECUTION);
    }

    private GenerationWorkspace fullStackWorkspace() throws Exception {
        Path workspaceRoot = Files.createDirectories(root.resolve("full-stack"));
        Path frontend = Files.createDirectories(workspaceRoot.resolve("frontend/src"));
        Path backend = Files.createDirectories(workspaceRoot.resolve("backend"));
        return new GenerationWorkspace(
                1L,
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                workspaceRoot,
                workspaceRoot,
                true,
                frontend.getParent(),
                backend,
                Set.of(),
                Set.of()
        );
    }

    private ProjectCommandResult successfulCommand() {
        return new ProjectCommandResult(
                ProjectCommandResult.Status.SUCCESS,
                "go test -mod=readonly -count=1 -trimpath -buildvcs=false ./...",
                0,
                "ok",
                null
        );
    }
}
