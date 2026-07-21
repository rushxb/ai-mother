package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.config.ArtifactLifecycleProperties;
import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.service.artifact.ArtifactDirectoryCopier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationExecutionWorkspaceServiceTest {

    private static final Long APP_ID = 11L;
    private static final CodeGenTypeEnum CODE_GEN_TYPE = CodeGenTypeEnum.VUE_PROJECT;

    @TempDir
    Path tempDirectory;

    @Test
    void materializationMustClampTimeoutAndPropagateTaskCancellationSupplier() throws Exception {
        Fixture fixture = fixture("task-workspace-policy");
        Duration configuredTimeout = Duration.ofMinutes(2);
        Duration clampedTimeout = Duration.ofSeconds(7);
        fixture.properties().setExecutionWorkspaceCopyTimeout(configuredTimeout);
        when(fixture.executionContextService().clampTimeout(
                fixture.fence().taskId(), configuredTimeout)).thenReturn(clampedTimeout);
        doAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            Files.createDirectory(target);
            return null;
        }).when(fixture.artifactDirectoryCopier()).copyExecutionWorkspace(
                any(Path.class),
                any(Path.class),
                any(Duration.class),
                any(BooleanSupplier.class)
        );

        GenerationExecutionWorkspace workspace = fixture.service().register(
                fixture.fence(), APP_ID, CODE_GEN_TYPE);

        assertEquals(fixture.fence(), workspace.fence());
        ArgumentCaptor<BooleanSupplier> cancellationCaptor =
                ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(fixture.artifactDirectoryCopier()).copyExecutionWorkspace(
                eq(fixture.canonicalRoot()),
                any(Path.class),
                eq(clampedTimeout),
                cancellationCaptor.capture()
        );
        when(fixture.executionContextService().shouldStop(fixture.fence().taskId())).thenReturn(true);
        assertTrue(cancellationCaptor.getValue().getAsBoolean());
    }

    @Test
    void cancellationDuringCopyMustPropagatePolicyAndDeletePartialTypeRoot() throws Exception {
        Fixture fixture = fixture("task-workspace-cancelled");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<Path> stagedTarget = new AtomicReference<>();
        doAnswer(invocation -> {
            if (cancelled.get()) {
                throw new GenerationExecutionCancelledException("user_requested");
            }
            return null;
        }).when(fixture.executionContextService()).assertCanContinue(fixture.fence().taskId());
        when(fixture.executionContextService().clampTimeout(any(String.class), any(Duration.class)))
                .thenReturn(Duration.ofSeconds(30));
        when(fixture.executionContextService().shouldStop(fixture.fence().taskId()))
                .thenAnswer(ignored -> cancelled.get());
        doAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            stagedTarget.set(target);
            Files.createDirectory(target);
            Files.writeString(target.resolve("partial.txt"), "partial");
            cancelled.set(true);
            throw new InterruptedException("cancelled while copying");
        }).when(fixture.artifactDirectoryCopier()).copyExecutionWorkspace(
                any(Path.class),
                any(Path.class),
                any(Duration.class),
                any(BooleanSupplier.class)
        );

        try {
            assertThrows(
                    GenerationExecutionCancelledException.class,
                    () -> fixture.service().register(fixture.fence(), APP_ID, CODE_GEN_TYPE)
            );
        } finally {
            Thread.interrupted();
        }

        assertFalse(Files.exists(stagedTarget.get()));
        assertFalse(Files.exists(stagedTarget.get().getParent()));
    }

    @Test
    void deadlineBeforeCopyMustNotStartTheCopier() throws Exception {
        Fixture fixture = fixture("task-workspace-deadline");
        when(fixture.executionContextService().clampTimeout(any(String.class), any(Duration.class)))
                .thenThrow(new GenerationDeadlineExceededException(fixture.fence().taskId()));

        assertThrows(
                GenerationDeadlineExceededException.class,
                () -> fixture.service().register(fixture.fence(), APP_ID, CODE_GEN_TYPE)
        );

        verify(fixture.artifactDirectoryCopier(), never()).copyExecutionWorkspace(
                any(Path.class),
                any(Path.class),
                any(Duration.class),
                any(BooleanSupplier.class)
        );
    }

    private Fixture fixture(String taskId) throws Exception {
        Path outputRoot = tempDirectory.resolve(taskId).resolve("output");
        Path canonicalRoot = Files.createDirectories(tempDirectory.resolve(taskId).resolve("canonical"));
        Files.writeString(canonicalRoot.resolve("package.json"), "{}");

        CodeStorageProperties storageProperties = new CodeStorageProperties();
        storageProperties.setOutputRootDir(outputRoot);
        storageProperties.setDeployRootDir(tempDirectory.resolve(taskId).resolve("deploy"));
        storageProperties.setSnapshotRootDir(tempDirectory.resolve(taskId).resolve("snapshot"));
        ArtifactLifecycleProperties lifecycleProperties = new ArtifactLifecycleProperties();
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        ArtifactDirectoryCopier copier = mock(ArtifactDirectoryCopier.class);
        GenerationExecutionContextService executionContextService =
                mock(GenerationExecutionContextService.class);
        GenerationWorkspace canonicalWorkspace = workspace(canonicalRoot, true);
        when(workspaceService.resolveCanonical(APP_ID, CODE_GEN_TYPE)).thenReturn(canonicalWorkspace);
        when(workspaceService.resolveExecutionWorkspace(
                eq(APP_ID), eq(CODE_GEN_TYPE), any(Path.class), eq(true)))
                .thenAnswer(invocation -> workspace(invocation.getArgument(2), true));

        GenerationExecutionFence fence = new GenerationExecutionFence(taskId, "worker-1", 1L);
        GenerationExecutionWorkspaceService service = new GenerationExecutionWorkspaceService(
                storageProperties,
                workspaceService,
                new GenerationWorkspaceExecutionScope(),
                copier,
                executionContextService,
                lifecycleProperties
        );
        return new Fixture(
                fence,
                canonicalRoot.toRealPath(),
                lifecycleProperties,
                copier,
                executionContextService,
                service
        );
    }

    private GenerationWorkspace workspace(Path root, boolean exists) {
        Path normalized = root.toAbsolutePath().normalize();
        return new GenerationWorkspace(
                APP_ID,
                CODE_GEN_TYPE,
                normalized,
                normalized,
                exists,
                normalized,
                null,
                Set.of(),
                Set.of("json", "vue", "ts")
        );
    }

    private record Fixture(
            GenerationExecutionFence fence,
            Path canonicalRoot,
            ArtifactLifecycleProperties properties,
            ArtifactDirectoryCopier artifactDirectoryCopier,
            GenerationExecutionContextService executionContextService,
            GenerationExecutionWorkspaceService service
    ) {
    }
}
