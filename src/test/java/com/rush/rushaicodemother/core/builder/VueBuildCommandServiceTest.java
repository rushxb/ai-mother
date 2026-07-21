package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandExecutor;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.service.dependency.DependencyInstallResult;
import com.rush.rushaicodemother.service.dependency.DependencyInstallMode;
import com.rush.rushaicodemother.service.dependency.ProjectDependencyInstaller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VueBuildCommandServiceTest {

    @TempDir
    Path projectRoot;

    private ProjectDependencyInstaller dependencyInstaller;
    private VueBuildStateStore stateStore;
    private VueBuildCommandService commandService;

    @BeforeEach
    void setUp() {
        dependencyInstaller = mock(ProjectDependencyInstaller.class);
        stateStore = mock(VueBuildStateStore.class);
        commandService = new VueBuildCommandService(
                dependencyInstaller,
                new GenerationPerformanceMonitorService(),
                mock(ProjectCommandExecutor.class),
                new ProjectCommandProperties(),
                stateStore
        );
    }

    @Test
    void shouldSkipInstallOnlyWhenDependencyDirectoryAndFingerprintAreReady() {
        VueBuildCommandResult result = commandService.installDependencies(
                projectRoot,
                true,
                "dependency",
                "task-cached"
        );

        assertTrue(result.success());
        verifyNoInteractions(dependencyInstaller, stateStore);
    }

    @Test
    void shouldInstallOnceAndKeepSuccessfulResultWhenStatePersistenceFails() throws Exception {
        when(dependencyInstaller.ensureInstalled(
                projectRoot, "task-install", DependencyInstallMode.REFRESH_FROM_LOCKFILE))
                .thenReturn(DependencyInstallResult.success("installed"));
        doThrow(new IOException("disk full"))
                .when(stateStore)
                .recordDependencyInstalled(projectRoot, "dependency");

        VueBuildCommandResult result = commandService.installDependencies(
                projectRoot,
                false,
                "dependency",
                "task-install"
        );

        assertTrue(result.success());
        verify(dependencyInstaller).ensureInstalled(
                projectRoot, "task-install", DependencyInstallMode.REFRESH_FROM_LOCKFILE);
        verify(stateStore).recordDependencyInstalled(projectRoot, "dependency");
        verify(dependencyInstaller, never()).ensureInstalled(projectRoot);
    }
}
