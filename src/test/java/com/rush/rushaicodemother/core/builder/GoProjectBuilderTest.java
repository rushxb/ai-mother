package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandResult;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoProjectBuilderTest {

    @TempDir
    Path projectRoot;

    private GoBuildCommandService commandService;
    private GoProjectSnapshotService snapshotService;
    private GoBuildResultRegistry resultRegistry;
    private GenerationExecutionContextService executionContextService;
    private GoProjectBuilder builder;
    private GoProjectSnapshot snapshot;

    @BeforeEach
    void setUp() {
        commandService = mock(GoBuildCommandService.class);
        snapshotService = mock(GoProjectSnapshotService.class);
        resultRegistry = new GoBuildResultRegistry(new ProjectCommandProperties());
        executionContextService = mock(GenerationExecutionContextService.class);
        builder = new GoProjectBuilder(
                commandService,
                snapshotService,
                resultRegistry,
                executionContextService
        );
        snapshot = new GoProjectSnapshot("snapshot-a");
    }

    @Test
    void shouldRejectProjectWithoutLockedDependenciesBeforeConsumingBudget() throws Exception {
        Files.writeString(projectRoot.resolve("go.mod"), "module example");

        GoBuildResult result = builder.buildProjectWithResult(projectRoot.toString(), "task-invalid");

        assertFalse(result.success());
        verify(executionContextService, never())
                .consumeIfPresent("task-invalid", GenerationBudgetKind.BUILD_EXECUTION);
        verify(commandService, never()).executeTests(projectRoot, "task-invalid");
    }

    @Test
    void shouldConsumeOneBudgetUnitBeforeRunningTests() throws Exception {
        Files.writeString(projectRoot.resolve("go.mod"), "module example");
        Files.writeString(projectRoot.resolve("go.sum"), "");
        when(snapshotService.capture(projectRoot)).thenReturn(snapshot);
        when(commandService.executeTests(projectRoot, "task-go")).thenReturn(new ProjectCommandResult(
                ProjectCommandResult.Status.SUCCESS,
                "go test -mod=readonly -count=1 -trimpath -buildvcs=false ./...",
                0,
                "ok",
                null
        ));

        GoBuildResult result = builder.buildProjectWithResult(projectRoot.toString(), "task-go");

        assertTrue(result.success());
        verify(executionContextService)
                .consumeIfPresent("task-go", GenerationBudgetKind.BUILD_EXECUTION);
        verify(commandService).executeTests(projectRoot, "task-go");
    }

    @Test
    void shouldReuseSuccessfulStableSnapshotWithinSameTask() throws Exception {
        prepareValidProject();
        when(snapshotService.capture(projectRoot)).thenReturn(snapshot);
        when(commandService.executeTests(projectRoot, "task-reuse")).thenReturn(successfulCommand());

        GoBuildResult first = builder.buildProjectWithResult(projectRoot.toString(), "task-reuse");
        GoBuildResult second = builder.buildProjectWithResult(projectRoot.toString(), "task-reuse");

        assertTrue(first.success());
        assertEquals("done", first.stage());
        assertTrue(second.success());
        assertEquals("reused", second.stage());
        verify(commandService).executeTests(projectRoot, "task-reuse");
        verify(executionContextService)
                .consumeIfPresent("task-reuse", GenerationBudgetKind.BUILD_EXECUTION);
    }

    @Test
    void shouldInvalidateCacheWhenSourceSnapshotChanges() throws Exception {
        prepareValidProject();
        GoProjectSnapshot changed = new GoProjectSnapshot("snapshot-b");
        when(snapshotService.capture(projectRoot)).thenReturn(snapshot, snapshot, changed, changed);
        when(commandService.executeTests(projectRoot, "task-change")).thenReturn(successfulCommand());

        GoBuildResult first = builder.buildProjectWithResult(projectRoot.toString(), "task-change");
        GoBuildResult second = builder.buildProjectWithResult(projectRoot.toString(), "task-change");

        assertTrue(first.success());
        assertTrue(second.success());
        assertEquals("done", second.stage());
        verify(commandService, times(2)).executeTests(projectRoot, "task-change");
        verify(executionContextService, times(2))
                .consumeIfPresent("task-change", GenerationBudgetKind.BUILD_EXECUTION);
    }

    @Test
    void shouldNeverCacheFailedBuildResult() throws Exception {
        prepareValidProject();
        when(snapshotService.capture(projectRoot)).thenReturn(snapshot);
        when(commandService.executeTests(projectRoot, "task-failed"))
                .thenReturn(failedCommand(), successfulCommand());

        GoBuildResult first = builder.buildProjectWithResult(projectRoot.toString(), "task-failed");
        GoBuildResult second = builder.buildProjectWithResult(projectRoot.toString(), "task-failed");

        assertFalse(first.success());
        assertTrue(second.success());
        assertEquals("done", second.stage());
        verify(commandService, times(2)).executeTests(projectRoot, "task-failed");
    }

    @Test
    void shouldRejectSuccessWhenSourceChangesDuringBuild() throws Exception {
        prepareValidProject();
        GoProjectSnapshot changed = new GoProjectSnapshot("snapshot-b");
        when(snapshotService.capture(projectRoot)).thenReturn(snapshot, changed);
        when(commandService.executeTests(projectRoot, "task-unstable")).thenReturn(successfulCommand());

        GoBuildResult result = builder.buildProjectWithResult(projectRoot.toString(), "task-unstable");

        assertFalse(result.success());
        assertEquals("snapshot", result.stage());
        assertEquals(0, resultRegistry.size());
    }

    @Test
    void shouldNotReuseSuccessfulResultAcrossTasks() throws Exception {
        prepareValidProject();
        when(snapshotService.capture(projectRoot)).thenReturn(snapshot);
        when(commandService.executeTests(projectRoot, "task-one")).thenReturn(successfulCommand());
        when(commandService.executeTests(projectRoot, "task-two")).thenReturn(successfulCommand());

        GoBuildResult first = builder.buildProjectWithResult(projectRoot.toString(), "task-one");
        GoBuildResult second = builder.buildProjectWithResult(projectRoot.toString(), "task-two");

        assertTrue(first.success());
        assertTrue(second.success());
        assertEquals("done", second.stage());
        verify(commandService).executeTests(projectRoot, "task-one");
        verify(commandService).executeTests(projectRoot, "task-two");
    }

    private void prepareValidProject() throws Exception {
        Files.writeString(projectRoot.resolve("go.mod"), "module example");
        Files.writeString(projectRoot.resolve("go.sum"), "");
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

    private ProjectCommandResult failedCommand() {
        return new ProjectCommandResult(
                ProjectCommandResult.Status.FAILED,
                "go test -mod=readonly -count=1 -trimpath -buildvcs=false ./...",
                1,
                "compile failed",
                "Go 测试命令退出码: 1"
        );
    }
}
