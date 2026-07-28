package com.rush.rushaicodemother.core.builder;

import cn.hutool.json.JSONObject;
import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.monitor.ProjectBuildCoordinationMetricsCollector;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        resultRegistry = new VueBuildResultRegistry(
                new ProjectCommandProperties(),
                ProjectBuildCoordinationMetricsCollector.noOp()
        );
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
        when(snapshotService.capture(
                eq(projectRoot), any(JSONObject.class), any(Runnable.class))).thenReturn(snapshot);
        when(scriptResolver.resolve(any(JSONObject.class)))
                .thenReturn(new VueProjectScripts("build", "pure-build", "type-check"));
    }

    @Test
    void shouldNotSwallowDeadlineWhileScanningBuildSnapshot() throws Exception {
        when(snapshotService.capture(
                eq(projectRoot), any(JSONObject.class), any(Runnable.class)))
                .thenThrow(new GenerationDeadlineExceededException("task-expired"));

        assertThrows(
                GenerationDeadlineExceededException.class,
                () -> builder.buildProjectWithResult(projectRoot.toString(), "task-expired")
        );
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
        assertEquals(1, resultRegistry.size());
        assertEquals(1, resultRegistry.reusableSize());
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
        assertEquals(0, resultRegistry.size());
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
        assertEquals(0, resultRegistry.size());
        assertEquals(0, resultRegistry.reusableSize());
    }

    @Test
    void shouldReuseSuccessfulStableBuildWithinSameTask() throws Exception {
        createDirectory("node_modules");
        createDirectory("dist");
        VueBuildState staleState = new VueBuildState("dependency", "old-critical", "presentation");
        when(stateStore.read(projectRoot)).thenReturn(staleState, VueBuildState.fromSnapshot(snapshot));
        when(commandService.installDependencies(projectRoot, true, "dependency", "task-same"))
                .thenReturn(VueBuildCommandResult.skipped("pnpm install", "cached"));
        when(commandService.executeFullBuild(any(), any(), eq("task-same")))
                .thenReturn(VueBuildCommandResult.success("pnpm run build", 0, "ok"));

        VueBuildResult first = builder.buildProjectWithResult(projectRoot.toString(), "task-same");
        VueBuildResult second = builder.buildProjectWithResult(projectRoot.toString(), "task-same");

        assertTrue(first.success());
        assertEquals("done", first.stage());
        assertTrue(second.success());
        assertEquals("task-reuse", second.stage());
        verify(commandService).executeFullBuild(eq(projectRoot), any(), eq("task-same"));
        verify(executionContextService)
                .consumeIfPresent("task-same", GenerationBudgetKind.BUILD_EXECUTION);
    }

    @Test
    void shouldMergeConcurrentBuilderCallsForSameTaskAndSnapshot() throws Exception {
        createDirectory("node_modules");
        createDirectory("dist");
        AtomicBoolean artifactCurrent = new AtomicBoolean();
        VueBuildState staleState = new VueBuildState("dependency", "old-critical", "presentation");
        when(stateStore.read(projectRoot)).thenAnswer(invocation -> artifactCurrent.get()
                ? VueBuildState.fromSnapshot(snapshot)
                : staleState);
        doAnswer(invocation -> {
            artifactCurrent.set(true);
            return null;
        }).when(stateStore).persist(projectRoot, snapshot);
        when(commandService.installDependencies(projectRoot, true, "dependency", "task-concurrent"))
                .thenReturn(VueBuildCommandResult.skipped("pnpm install", "cached"));
        when(commandService.executeFullBuild(any(), any(), eq("task-concurrent"))).thenAnswer(invocation -> {
            Thread.sleep(100);
            return VueBuildCommandResult.success("pnpm run build", 0, "ok");
        });
        CountDownLatch callersReady = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<VueBuildResult>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> {
                    callersReady.countDown();
                    start.await();
                    return builder.buildProjectWithResult(projectRoot.toString(), "task-concurrent");
                }));
            }
            callersReady.await();
            start.countDown();

            for (Future<VueBuildResult> future : futures) {
                assertTrue(future.get().success());
            }
        }

        verify(commandService).executeFullBuild(eq(projectRoot), any(), eq("task-concurrent"));
        verify(executionContextService)
                .consumeIfPresent("task-concurrent", GenerationBudgetKind.BUILD_EXECUTION);
        assertEquals(1, resultRegistry.reusableSize());
        assertEquals(0, resultRegistry.inFlightSize());
    }

    @Test
    void shouldNotReuseSuccessfulBuildAcrossTasks() throws Exception {
        createDirectory("node_modules");
        createDirectory("dist");
        when(stateStore.read(projectRoot))
                .thenReturn(new VueBuildState("dependency", "old-critical", "presentation"));
        when(commandService.installDependencies(eq(projectRoot), eq(true), eq("dependency"), any()))
                .thenReturn(VueBuildCommandResult.skipped("pnpm install", "cached"));
        when(commandService.executeFullBuild(eq(projectRoot), any(), any()))
                .thenReturn(VueBuildCommandResult.success("pnpm run build", 0, "ok"));

        VueBuildResult first = builder.buildProjectWithResult(projectRoot.toString(), "task-one");
        VueBuildResult second = builder.buildProjectWithResult(projectRoot.toString(), "task-two");

        assertTrue(first.success());
        assertTrue(second.success());
        assertEquals("done", second.stage());
        verify(commandService, times(2)).executeFullBuild(eq(projectRoot), any(), any());
        verify(executionContextService)
                .consumeIfPresent("task-one", GenerationBudgetKind.BUILD_EXECUTION);
        verify(executionContextService)
                .consumeIfPresent("task-two", GenerationBudgetKind.BUILD_EXECUTION);
    }

    @Test
    void shouldRebuildWhenCachedSuccessNoLongerMatchesArtifactState() throws Exception {
        createDirectory("node_modules");
        createDirectory("dist");
        VueBuildState staleState = new VueBuildState("dependency", "old-critical", "presentation");
        VueBuildState otherArtifactState = new VueBuildState("dependency", "other-critical", "presentation");
        when(stateStore.read(projectRoot)).thenReturn(staleState, otherArtifactState, otherArtifactState);
        when(commandService.installDependencies(projectRoot, true, "dependency", "task-artifact"))
                .thenReturn(VueBuildCommandResult.skipped("pnpm install", "cached"));
        when(commandService.executeFullBuild(any(), any(), eq("task-artifact")))
                .thenReturn(VueBuildCommandResult.success("pnpm run build", 0, "ok"));

        VueBuildResult first = builder.buildProjectWithResult(projectRoot.toString(), "task-artifact");
        VueBuildResult second = builder.buildProjectWithResult(projectRoot.toString(), "task-artifact");

        assertTrue(first.success());
        assertTrue(second.success());
        assertEquals("done", second.stage());
        verify(commandService, times(2)).executeFullBuild(eq(projectRoot), any(), eq("task-artifact"));
        verify(executionContextService, times(2))
                .consumeIfPresent("task-artifact", GenerationBudgetKind.BUILD_EXECUTION);
    }

    @Test
    void shouldNeverCacheFailedBuildResult() throws Exception {
        createDirectory("node_modules");
        createDirectory("dist");
        when(stateStore.read(projectRoot))
                .thenReturn(new VueBuildState("dependency", "old-critical", "presentation"));
        when(commandService.installDependencies(projectRoot, true, "dependency", "task-failed"))
                .thenReturn(VueBuildCommandResult.skipped("pnpm install", "cached"));
        when(commandService.executeFullBuild(any(), any(), eq("task-failed"))).thenReturn(
                VueBuildCommandResult.failed("pnpm run build", 1, "failed"),
                VueBuildCommandResult.success("pnpm run build", 0, "ok")
        );

        VueBuildResult first = builder.buildProjectWithResult(projectRoot.toString(), "task-failed");
        VueBuildResult second = builder.buildProjectWithResult(projectRoot.toString(), "task-failed");

        assertFalse(first.success());
        assertTrue(second.success());
        assertEquals("done", second.stage());
        verify(commandService, times(2)).executeFullBuild(eq(projectRoot), any(), eq("task-failed"));
        assertEquals(1, resultRegistry.reusableSize());
    }

    @Test
    void shouldRejectSuccessWhenSourceChangesDuringBuild() throws Exception {
        createDirectory("node_modules");
        createDirectory("dist");
        VueProjectSnapshot changedSnapshot = new VueProjectSnapshot(
                "dependency",
                "changed-critical",
                "presentation"
        );
        when(snapshotService.capture(eq(projectRoot), any(JSONObject.class), any(Runnable.class)))
                .thenReturn(snapshot, changedSnapshot);
        when(stateStore.read(projectRoot))
                .thenReturn(new VueBuildState("dependency", "old-critical", "presentation"));
        when(commandService.installDependencies(projectRoot, true, "dependency", "task-unstable"))
                .thenReturn(VueBuildCommandResult.skipped("pnpm install", "cached"));
        when(commandService.executeFullBuild(any(), any(), eq("task-unstable")))
                .thenReturn(VueBuildCommandResult.success("pnpm run build", 0, "ok"));

        VueBuildResult result = builder.buildProjectWithResult(projectRoot.toString(), "task-unstable");

        assertFalse(result.success());
        assertEquals("snapshot", result.stage());
        verify(stateStore, never()).persist(any(), any());
        assertEquals(0, resultRegistry.reusableSize());
        assertEquals(0, resultRegistry.size());
    }

    @Test
    void shouldRejectDistReuseWhenSourceChangesDuringValidation() throws Exception {
        createDirectory("node_modules");
        createDirectory("dist");
        VueProjectSnapshot changedSnapshot = new VueProjectSnapshot(
                "dependency",
                "critical",
                "changed-presentation"
        );
        when(snapshotService.capture(eq(projectRoot), any(JSONObject.class), any(Runnable.class)))
                .thenReturn(snapshot, changedSnapshot);
        when(stateStore.read(projectRoot)).thenReturn(VueBuildState.fromSnapshot(snapshot));

        VueBuildResult result = builder.buildProjectWithResult(projectRoot.toString(), "task-reuse-unstable");

        assertFalse(result.success());
        assertEquals("snapshot", result.stage());
        verifyNoInteractions(commandService);
        verify(executionContextService, never())
                .consumeIfPresent("task-reuse-unstable", GenerationBudgetKind.BUILD_EXECUTION);
        verify(stateStore, never()).persist(any(), any());
        assertEquals(0, resultRegistry.reusableSize());
    }

    @Test
    void shouldReturnRecentResultWhenCurrentSnapshotMatchesRegistryKey() throws Exception {
        createDirectory("node_modules");
        createDirectory("dist");
        when(stateStore.read(projectRoot)).thenReturn(VueBuildState.fromSnapshot(snapshot));
        VueBuildResult expected = VueBuildResult.reused(projectRoot.toString());
        resultRegistry.rememberSuccessful(projectRoot, snapshot, expected);

        VueBuildResult result = builder.getRecentBuildResult(projectRoot.toString());

        assertSame(expected, result);
    }

    @Test
    void shouldHideRecentResultWhenArtifactStateNoLongerMatches() throws Exception {
        createDirectory("node_modules");
        createDirectory("dist");
        when(stateStore.read(projectRoot))
                .thenReturn(new VueBuildState("dependency", "other-critical", "presentation"));
        resultRegistry.rememberSuccessful(projectRoot, snapshot, VueBuildResult.reused(projectRoot.toString()));

        VueBuildResult result = builder.getRecentBuildResult(projectRoot.toString());

        assertNull(result);
    }

    @Test
    void shouldReturnNullWhenCurrentSnapshotHasChanged() throws Exception {
        VueProjectSnapshot changedSnapshot = new VueProjectSnapshot(
                "dependency",
                "changed-critical",
                "presentation"
        );
        when(snapshotService.capture(eq(projectRoot), any(JSONObject.class))).thenReturn(changedSnapshot);

        VueBuildResult result = builder.getRecentBuildResult(projectRoot.toString());

        assertNull(result);
    }

    @Test
    void shouldReturnNullForInvalidRecentBuildProjectPath() {
        assertNull(builder.getRecentBuildResult("\0invalid"));

        assertEquals(0, resultRegistry.size());
    }

    private void createDirectory(String relativePath) throws Exception {
        Files.createDirectories(projectRoot.resolve(relativePath));
    }
}
