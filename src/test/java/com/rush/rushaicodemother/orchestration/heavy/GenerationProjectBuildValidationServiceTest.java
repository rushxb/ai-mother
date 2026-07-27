package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.core.builder.BuildExecutionBudgetReservation;
import com.rush.rushaicodemother.core.builder.GoBuildResult;
import com.rush.rushaicodemother.core.builder.GoProjectBuilder;
import com.rush.rushaicodemother.core.builder.VueBuildResult;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandResult;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationProjectBuildValidationServiceTest {

    @TempDir
    Path root;

    @Test
    void fullStackMustValidateBothComponentsWithOneSharedBudgetReservation() throws Exception {
        GenerationWorkspace workspace = fullStackWorkspace();
        VueProjectBuilder vueBuilder = mock(VueProjectBuilder.class);
        GoProjectBuilder goBuilder = mock(GoProjectBuilder.class);
        GenerationExecutionContextService contextService = mock(GenerationExecutionContextService.class);
        CyclicBarrier componentStartBarrier = new CyclicBarrier(2);
        when(goBuilder.buildProjectWithResult(eq(workspace.backendRootPath().toString()), eq("task-full"), any()))
                .thenAnswer(invocation -> {
                    awaitBothComponents(componentStartBarrier);
                    BuildExecutionBudgetReservation reservation = invocation.getArgument(2);
                    reservation.reserve();
                    return failedGo(workspace.backendRootPath());
                });
        when(vueBuilder.buildProjectWithResult(eq(workspace.frontendRootPath().toString()), eq("task-full"), any()))
                .thenAnswer(invocation -> {
                    awaitBothComponents(componentStartBarrier);
                    BuildExecutionBudgetReservation reservation = invocation.getArgument(2);
                    reservation.reserve();
                    return successfulVue(workspace.frontendRootPath());
                });
        GenerationProjectBuildValidationService service = new GenerationProjectBuildValidationService(
                vueBuilder, goBuilder, contextService);

        ProjectBuildValidationResult result = service.validate(
                workspace, CodeGenTypeEnum.FULL_STACK_PROJECT, "task-full");

        assertFalse(result.success());
        assertTrue(result.report().contains("后端构建测试"));
        assertTrue(result.report().contains("前端构建验证"));
        ArgumentCaptor<BuildExecutionBudgetReservation> goReservation =
                ArgumentCaptor.forClass(BuildExecutionBudgetReservation.class);
        ArgumentCaptor<BuildExecutionBudgetReservation> vueReservation =
                ArgumentCaptor.forClass(BuildExecutionBudgetReservation.class);
        verify(goBuilder).buildProjectWithResult(
                eq(workspace.backendRootPath().toString()), eq("task-full"), goReservation.capture());
        verify(vueBuilder).buildProjectWithResult(
                eq(workspace.frontendRootPath().toString()), eq("task-full"), vueReservation.capture());
        assertSame(goReservation.getValue(), vueReservation.getValue());
        verify(contextService).consumeIfPresent("task-full", GenerationBudgetKind.BUILD_EXECUTION);
    }

    @Test
    void componentPolicyFailureMustCancelTheOtherBuild() throws Exception {
        GenerationWorkspace workspace = fullStackWorkspace();
        VueProjectBuilder vueBuilder = mock(VueProjectBuilder.class);
        GoProjectBuilder goBuilder = mock(GoProjectBuilder.class);
        GenerationExecutionContextService contextService = mock(GenerationExecutionContextService.class);
        CountDownLatch frontendStarted = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicBoolean frontendInterrupted = new AtomicBoolean();
        GenerationExecutionPolicyException backendFailure =
                new GenerationExecutionPolicyException("后端构建已超过任务截止时间");
        when(goBuilder.buildProjectWithResult(eq(workspace.backendRootPath().toString()), eq("task-cancel"), any()))
                .thenAnswer(invocation -> {
                    if (!frontendStarted.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("前端构建没有并发启动");
                    }
                    throw backendFailure;
                });
        when(vueBuilder.buildProjectWithResult(
                eq(workspace.frontendRootPath().toString()), eq("task-cancel"), any()))
                .thenAnswer(invocation -> {
                    frontendStarted.countDown();
                    try {
                        if (!neverReleased.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("异常后未取消仍在运行的前端构建");
                        }
                        return successfulVue(workspace.frontendRootPath());
                    } catch (InterruptedException exception) {
                        frontendInterrupted.set(true);
                        Thread.currentThread().interrupt();
                        throw new GenerationExecutionPolicyException("前端构建已取消");
                    }
                });
        GenerationProjectBuildValidationService service = new GenerationProjectBuildValidationService(
                vueBuilder, goBuilder, contextService);

        GenerationExecutionPolicyException actual = assertThrows(
                GenerationExecutionPolicyException.class,
                () -> service.validate(
                        workspace, CodeGenTypeEnum.FULL_STACK_PROJECT, "task-cancel")
        );

        assertSame(backendFailure, actual);
        assertTrue(frontendInterrupted.get());
    }

    @Test
    void backendProjectMustNotInvokeVueBuilder() throws Exception {
        Path backend = Files.createDirectories(root.resolve("backend-only"));
        GenerationWorkspace workspace = new GenerationWorkspace(
                1L,
                CodeGenTypeEnum.BACKEND_PROJECT,
                backend,
                backend,
                true,
                backend,
                backend,
                Set.of(),
                Set.of()
        );
        VueProjectBuilder vueBuilder = mock(VueProjectBuilder.class);
        GoProjectBuilder goBuilder = mock(GoProjectBuilder.class);
        GenerationExecutionContextService contextService = mock(GenerationExecutionContextService.class);
        when(goBuilder.buildProjectWithResult(eq(backend.toString()), eq("task-backend"), any()))
                .thenReturn(successfulGo(backend));
        GenerationProjectBuildValidationService service = new GenerationProjectBuildValidationService(
                vueBuilder, goBuilder, contextService);

        ProjectBuildValidationResult result = service.validate(
                workspace, CodeGenTypeEnum.BACKEND_PROJECT, "task-backend");

        assertTrue(result.success());
        verify(vueBuilder, never()).buildProjectWithResult(any(), any(), any());
    }

    private GenerationWorkspace fullStackWorkspace() throws Exception {
        Path workspaceRoot = Files.createDirectories(root.resolve("full-stack"));
        Path frontend = Files.createDirectories(workspaceRoot.resolve("frontend"));
        Path backend = Files.createDirectories(workspaceRoot.resolve("backend"));
        return new GenerationWorkspace(
                1L,
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                workspaceRoot,
                workspaceRoot,
                true,
                frontend,
                backend,
                Set.of(),
                Set.of()
        );
    }

    private GoBuildResult successfulGo(Path path) {
        return new GoBuildResult(true, "done", path.toString(), "Go 项目构建测试通过",
                command(ProjectCommandResult.Status.SUCCESS, 0, "ok"));
    }

    private GoBuildResult failedGo(Path path) {
        return new GoBuildResult(false, "test", path.toString(), "Go 项目编译或测试未通过",
                command(ProjectCommandResult.Status.FAILED, 1, "compile failed"));
    }

    private VueBuildResult successfulVue(Path path) {
        return new VueBuildResult(true, "done", path.toString(), "Vue 项目构建成功", null, null);
    }

    private ProjectCommandResult command(ProjectCommandResult.Status status, int exitCode, String output) {
        return new ProjectCommandResult(
                status,
                "go test -mod=readonly -count=1 -trimpath -buildvcs=false ./...",
                exitCode,
                output,
                status == ProjectCommandResult.Status.SUCCESS ? null : "退出码 1"
        );
    }

    private void awaitBothComponents(CyclicBarrier barrier) {
        try {
            barrier.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待全栈构建组件并发启动时被中断", exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new AssertionError("全栈前后端构建没有并发启动", exception);
        }
    }
}
