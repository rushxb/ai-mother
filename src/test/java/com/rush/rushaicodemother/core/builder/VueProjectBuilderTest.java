package com.rush.rushaicodemother.core.builder;

import cn.hutool.json.JSONObject;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VueProjectBuilderTest {

    @TempDir
    Path projectRoot;

    private VueProjectSnapshotService snapshotService;
    private VueBuildStateStore stateStore;
    private VueProjectScriptResolver scriptResolver;
    private VueBuildCommandService commandService;
    private VueBuildResultRegistry resultRegistry;
    private GenerationExecutionContextService executionContextService;
    private VueProjectBuilder builder;
    private VueProjectSnapshot snapshot;

    @BeforeEach
    void setUp() throws Exception {
        snapshotService = mock(VueProjectSnapshotService.class);
        stateStore = mock(VueBuildStateStore.class);
        scriptResolver = mock(VueProjectScriptResolver.class);
        commandService = mock(VueBuildCommandService.class);
        resultRegistry = mock(VueBuildResultRegistry.class);
        executionContextService = mock(GenerationExecutionContextService.class);
        builder = new VueProjectBuilder(
                snapshotService,
                stateStore,
                scriptResolver,
                commandService,
                resultRegistry,
                executionContextService
        );
        snapshot = new VueProjectSnapshot("dependency", "critical", "presentation");
        Files.writeString(
                projectRoot.resolve("package.json"),
                "{\"scripts\":{\"build\":\"vite build\"}}",
                StandardCharsets.UTF_8
        );
        when(snapshotService.capture(eq(projectRoot), any(JSONObject.class))).thenReturn(snapshot);
        when(scriptResolver.resolve(any(JSONObject.class)))
                .thenReturn(new VueProjectScripts("build", "pure-build", "type-check"));
    }

    @Test
    void shouldReuseDistWithoutConsumingBudgetWhenSnapshotIsUnchanged() throws Exception {
        createDirectory("node_modules");
        createDirectory("dist");
        when(stateStore.read(projectRoot)).thenReturn(VueBuildState.fromSnapshot(snapshot));

        VueBuildResult result = builder.buildProjectWithResult(projectRoot.toString(), "task-reuse");

        assertTrue(result.success());
        assertEquals("reuse", result.stage());
        verifyNoInteractions(commandService);
        verify(executionContextService, never())
                .consumeIfPresent("task-reuse", GenerationBudgetKind.BUILD_EXECUTION);
        verify(resultRegistry).remember(projectRoot, snapshot, result);
    }

    @Test
    void shouldUseLightBuildForPresentationOnlyChanges() throws Exception {
        createDirectory("node_modules");
        createDirectory("dist");
        when(stateStore.read(projectRoot)).thenReturn(new VueBuildState("dependency", "critical", "old-presentation"));
        VueBuildCommandResult installResult = VueBuildCommandResult.skipped("pnpm install", "cached");
        when(commandService.installDependencies(projectRoot, true, "dependency", "task-presentation"))
                .thenReturn(installResult);
        when(commandService.executeLightValidation(any(), any(), eq("task-presentation")))
                .thenReturn(VueBuildCommandResult.success("pnpm run type-check", 0, "ok"));
        when(commandService.executeLightBuild(any(), any(), eq("task-presentation")))
                .thenReturn(VueBuildCommandResult.success("pnpm run pure-build", 0, "ok"));

        VueBuildResult result = builder.buildProjectWithResult(projectRoot.toString(), "task-presentation");

        assertTrue(result.success());
        assertEquals("light-done", result.stage());
        verify(commandService).executeLightBuild(eq(projectRoot), any(), eq("task-presentation"));
        verify(commandService, never()).executeFullBuild(any(), any(), any());
        verify(stateStore).persist(projectRoot, snapshot);
    }

    @Test
    void shouldUseLightBuildAfterDependencyOnlyRefresh() throws Exception {
        createDirectory("node_modules");
        createDirectory("dist");
        when(stateStore.read(projectRoot)).thenReturn(new VueBuildState("old-dependency", "critical", "presentation"));
        when(commandService.installDependencies(projectRoot, false, "dependency", "task-dependency"))
                .thenReturn(VueBuildCommandResult.success("pnpm install", 0, "installed"));
        when(commandService.executeLightValidation(any(), any(), eq("task-dependency")))
                .thenReturn(VueBuildCommandResult.success("pnpm run type-check", 0, "ok"));
        when(commandService.executeLightBuild(any(), any(), eq("task-dependency")))
                .thenReturn(VueBuildCommandResult.success("pnpm run pure-build", 0, "ok"));

        VueBuildResult result = builder.buildProjectWithResult(projectRoot.toString(), "task-dependency");

        assertTrue(result.success());
        assertEquals("dependency-refresh", result.stage());
        verify(commandService).executeLightBuild(eq(projectRoot), any(), eq("task-dependency"));
        verify(commandService, never()).executeFullBuild(any(), any(), any());
    }

    @Test
    void shouldUseFullBuildForCriticalSourceChanges() throws Exception {
        createDirectory("node_modules");
        createDirectory("dist");
        when(stateStore.read(projectRoot)).thenReturn(new VueBuildState("dependency", "old-critical", "presentation"));
        when(commandService.installDependencies(projectRoot, true, "dependency", "task-critical"))
                .thenReturn(VueBuildCommandResult.skipped("pnpm install", "cached"));
        when(commandService.executeFullBuild(any(), any(), eq("task-critical")))
                .thenReturn(VueBuildCommandResult.success("pnpm run build", 0, "ok"));

        VueBuildResult result = builder.buildProjectWithResult(projectRoot.toString(), "task-critical");

        assertTrue(result.success());
        assertEquals("done", result.stage());
        verify(commandService).executeFullBuild(eq(projectRoot), any(), eq("task-critical"));
        verify(commandService, never()).executeLightBuild(any(), any(), any());
    }

    @Test
    void shouldInstallDependenciesWhenNodeModulesIsMissingEvenIfFingerprintMatches() throws Exception {
        createDirectory("dist");
        when(stateStore.read(projectRoot)).thenReturn(VueBuildState.fromSnapshot(snapshot));
        when(commandService.installDependencies(projectRoot, false, "dependency", "task-missing-modules"))
                .thenReturn(VueBuildCommandResult.success("pnpm install", 0, "installed"));
        when(commandService.executeFullBuild(any(), any(), eq("task-missing-modules")))
                .thenReturn(VueBuildCommandResult.success("pnpm run build", 0, "ok"));

        VueBuildResult result = builder.buildProjectWithResult(projectRoot.toString(), "task-missing-modules");

        assertTrue(result.success());
        assertEquals("done", result.stage());
        verify(commandService).installDependencies(projectRoot, false, "dependency", "task-missing-modules");
    }

    @Test
    void shouldRejectInvalidPackageJsonBeforeInvokingBuildCommands() throws Exception {
        Files.writeString(projectRoot.resolve("package.json"), "{invalid-json", StandardCharsets.UTF_8);

        VueBuildResult result = builder.buildProjectWithResult(projectRoot.toString(), "task-invalid-package");

        assertFalse(result.success());
        assertEquals("prepare", result.stage());
        assertEquals("package.json \u89e3\u6790\u5931\u8d25", result.summary());
        verify(snapshotService, never()).capture(any(), any());
        verifyNoInteractions(commandService);
        verifyNoInteractions(resultRegistry);
    }

    @Test
    void shouldFailAtDistStageWhenSuccessfulFullBuildDoesNotCreateDist() throws Exception {
        createDirectory("node_modules");
        when(stateStore.read(projectRoot))
                .thenReturn(new VueBuildState("dependency", "old-critical", "presentation"));
        VueBuildCommandResult installResult = VueBuildCommandResult.skipped("pnpm install", "cached");
        VueBuildCommandResult buildResult = VueBuildCommandResult.success("pnpm run build", 0, "ok");
        when(commandService.installDependencies(projectRoot, true, "dependency", "task-no-dist"))
                .thenReturn(installResult);
        when(commandService.executeFullBuild(any(), any(), eq("task-no-dist"))).thenReturn(buildResult);

        VueBuildResult result = builder.buildProjectWithResult(projectRoot.toString(), "task-no-dist");

        assertFalse(result.success());
        assertEquals("dist", result.stage());
        assertSame(buildResult, result.buildResult());
        verify(stateStore, never()).persist(any(), any());
        verify(resultRegistry).remember(projectRoot, snapshot, result);
    }

    @Test
    void shouldReturnRecentResultWhenCurrentSnapshotMatchesRegistryKey() throws Exception {
        VueBuildResult expected = VueBuildResult.reused(projectRoot.toString());
        when(resultRegistry.find(projectRoot, snapshot)).thenReturn(expected);

        VueBuildResult result = builder.getRecentBuildResult(projectRoot.toString());

        assertSame(expected, result);
        verify(resultRegistry).find(projectRoot, snapshot);
    }

    @Test
    void shouldReturnNullWhenCurrentSnapshotHasChanged() throws Exception {
        VueProjectSnapshot changedSnapshot = new VueProjectSnapshot(
                "dependency",
                "changed-critical",
                "presentation"
        );
        when(snapshotService.capture(eq(projectRoot), any(JSONObject.class))).thenReturn(changedSnapshot);
        when(resultRegistry.find(projectRoot, changedSnapshot)).thenReturn(null);

        VueBuildResult result = builder.getRecentBuildResult(projectRoot.toString());

        assertNull(result);
        verify(resultRegistry).find(projectRoot, changedSnapshot);
    }

    @Test
    void shouldReturnNullForInvalidRecentBuildProjectPath() {
        assertNull(builder.getRecentBuildResult("\0invalid"));

        verifyNoInteractions(resultRegistry);
    }

    private void createDirectory(String relativePath) throws Exception {
        Files.createDirectories(projectRoot.resolve(relativePath));
    }
}
