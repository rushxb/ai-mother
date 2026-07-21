package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxProcessPlan;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.dependency.DependencyInstallResult;
import com.rush.rushaicodemother.service.dependency.ProjectDependencyInstaller;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionClaimResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevServerManagerDurableLeaseTest {

    private ProjectDependencyInstaller dependencyInstaller;
    private DevServerProjectLocator projectLocator;
    private DevServerPortAllocator portAllocator;
    private VisualEditorBootstrapInjector bootstrapInjector;
    private DevServerProcessRunner processRunner;
    private DevServerOutputHub outputHub;
    private DevServerSessionLeaseCoordinator leaseCoordinator;
    private DevServerManager manager;
    private Path project;

    @BeforeEach
    void setUp() {
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        dependencyInstaller = mock(ProjectDependencyInstaller.class);
        projectLocator = mock(DevServerProjectLocator.class);
        portAllocator = mock(DevServerPortAllocator.class);
        bootstrapInjector = mock(VisualEditorBootstrapInjector.class);
        processRunner = mock(DevServerProcessRunner.class);
        outputHub = mock(DevServerOutputHub.class);
        leaseCoordinator = mock(DevServerSessionLeaseCoordinator.class);
        manager = new DevServerManager(
                properties,
                dependencyInstaller,
                projectLocator,
                portAllocator,
                bootstrapInjector,
                processRunner,
                outputHub,
                leaseCoordinator
        );
        project = Path.of("target", "durable-dev-server-test").toAbsolutePath().normalize();
        when(projectLocator.locate(any())).thenReturn(project);
        when(portAllocator.reserve(11L, null)).thenReturn(5180);
    }

    @Test
    void crossNodeClaimConflictMustPreventDuplicateProcessStartAndReleaseThePort() {
        when(leaseCoordinator.claimStarting(11L, 7L, project, 5180))
                .thenReturn(DevServerSessionClaimResult.ACTIVE_SESSION_EXISTS);

        assertThrows(RuntimeException.class, () -> manager.startDevServer(app(), 7L));

        verify(portAllocator).release(11L);
        verify(dependencyInstaller, never()).ensureInstalled(any());
        verify(processRunner, never()).start(any(), anyInt(), anyLong(), any(), any());
    }

    @Test
    void runningTransitionMustPersistBothDevServerAndGatewayResourceIds() {
        Process process = liveProcess();
        SandboxProcessPlan plan = new SandboxProcessPlan(
                "container",
                project,
                List.of("docker", "run"),
                Map.of(),
                Set.of(),
                "dev-container",
                List.of(),
                List.of("gateway-container", "dev-container")
        );
        DevServerProcessSession processSession = new DevServerProcessSession(
                project, 5180, process, CompletableFuture.completedFuture(null), plan);
        stubSuccessfulDurableStart(processSession);

        manager.startDevServer(app(), 7L);

        verify(leaseCoordinator).markRunning(
                11L, "container", List.of("gateway-container", "dev-container"));
    }

    @Test
    void lostLeaseMustFenceAndStopTheLocalProcess() {
        DevServerProcessSession processSession = new DevServerProcessSession(
                project, 5180, liveProcess(), CompletableFuture.completedFuture(null));
        stubSuccessfulDurableStart(processSession);
        manager.startDevServer(app(), 7L);
        when(leaseCoordinator.renew(11L))
                .thenReturn(DevServerSessionLeaseCoordinator.LeaseStatus.LOST);

        manager.maintainSessionLeases();

        verify(processRunner).stop(eq(processSession));
        verify(leaseCoordinator).release(eq(11L), any());
    }

    private void stubSuccessfulDurableStart(DevServerProcessSession processSession) {
        when(leaseCoordinator.claimStarting(11L, 7L, project, 5180))
                .thenReturn(DevServerSessionClaimResult.ACQUIRED);
        when(leaseCoordinator.markRunning(eq(11L), any(), any())).thenReturn(true);
        when(dependencyInstaller.ensureInstalled(project)).thenReturn(DependencyInstallResult.success("ok"));
        when(outputHub.sink(11L)).thenReturn(line -> { });
        when(processRunner.start(eq(project), eq(5180), eq(11L), any(), any()))
                .thenReturn(processSession);
    }

    private Process liveProcess() {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        when(process.onExit()).thenReturn(new CompletableFuture<>());
        return process;
    }

    private App app() {
        return App.builder()
                .id(11L)
                .userId(7L)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .build();
    }
}
