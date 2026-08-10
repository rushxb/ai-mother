package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.dependency.DependencyInstallResult;
import com.rush.rushaicodemother.service.dependency.ProjectDependencyInstaller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dev Server 空闲与失效会话回收回归。
 *
 * <p>回收前项目里没有任何空闲回收：心跳只会无限续租，恢复扫描只处理租约已过期的记录。
 * 因此任何漏掉的停止都会让 Vite 进程、端口和单用户会话配额一直占用到 JVM 退出。</p>
 */
class DevServerManagerIdleReclamationTest {

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(20);
    private static final long START_NANOS = 1_000_000_000L;

    private Path tempDirectory;
    private Path project;
    private DevServerRuntimeProperties properties;
    private ProjectDependencyInstaller dependencyInstaller;
    private DevServerProjectLocator projectLocator;
    private DevServerPortAllocator portAllocator;
    private DevServerProcessRunner processRunner;
    private DevServerOutputHub outputHub;
    private DevServerSessionLeaseCoordinator leaseCoordinator;
    private DevServerManager manager;
    private AtomicLong nanos;
    private ProcessFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        tempDirectory = DevServerTestWorkspace.create("idle-reclaim");
        project = Files.createDirectories(tempDirectory.resolve("project-11"));
        properties = new DevServerRuntimeProperties();
        properties.setIdleSessionTimeout(IDLE_TIMEOUT);
        properties.setStopTimeout(Duration.ofMillis(200));
        dependencyInstaller = mock(ProjectDependencyInstaller.class);
        projectLocator = mock(DevServerProjectLocator.class);
        portAllocator = mock(DevServerPortAllocator.class);
        processRunner = mock(DevServerProcessRunner.class);
        outputHub = mock(DevServerOutputHub.class);
        leaseCoordinator = mock(DevServerSessionLeaseCoordinator.class);
        nanos = new AtomicLong(START_NANOS);
        LongSupplier nanoTimeSource = nanos::get;
        manager = new DevServerManager(
                properties,
                dependencyInstaller,
                projectLocator,
                portAllocator,
                mock(VisualEditorBootstrapInjector.class),
                processRunner,
                outputHub,
                leaseCoordinator,
                nanoTimeSource
        );
        fixture = startRunningSession();
    }

    @AfterEach
    void tearDown() throws Exception {
        DevServerTestWorkspace.delete(tempDirectory);
    }

    @Test
    void idleSessionMustBeReclaimedByTheHeartbeatSweep() {
        advance(IDLE_TIMEOUT.plusSeconds(1));

        manager.maintainSessionLeases();

        verify(processRunner).stop(eq(fixture.processSession()));
        verify(portAllocator).release(11L);
        assertFalse(manager.isRunning(11L));
    }

    @Test
    void reclaimedSessionMustNotHaveItsLeaseRenewedInTheSamePass() {
        advance(IDLE_TIMEOUT.plusSeconds(1));

        manager.maintainSessionLeases();

        // 续租发生在回收之后就等于给本轮该回收的会话又续了一个租期，回收会被无限推迟。
        verify(leaseCoordinator, never()).renew(anyLong());
        verify(leaseCoordinator).release(eq(11L), any());
    }

    @Test
    void accessingThePreviewMustPostponeReclamation() {
        advance(IDLE_TIMEOUT.minusMinutes(1));
        manager.touchSession(11L);
        advance(IDLE_TIMEOUT.minusMinutes(1));

        manager.maintainSessionLeases();

        verify(processRunner, never()).stop(any());
        verify(leaseCoordinator).renew(11L);
        assertTrue(manager.isRunning(11L));
    }

    @Test
    void activeSessionWithinTheIdleWindowMustSurviveTheSweep() {
        advance(IDLE_TIMEOUT.minusSeconds(1));

        manager.maintainSessionLeases();

        verify(processRunner, never()).stop(any());
        assertTrue(manager.isRunning(11L));
    }

    /**
     * 发布会把执行工作区整体移走，Linux 上 Vite 进程仍持有旧 inode。
     *
     * <p>若不回收，预览既不报错也不更新，用户看到的是一份无声的过期内容 —— 比直接失败更糟。</p>
     */
    @Test
    void sessionWhoseWorkspaceWasMovedAwayMustBeReclaimedEvenWhenNotIdle() throws Exception {
        Files.delete(project);

        manager.maintainSessionLeases();

        verify(processRunner).stop(eq(fixture.processSession()));
        verify(leaseCoordinator, never()).renew(anyLong());
        assertFalse(manager.isRunning(11L));
    }

    @Test
    void disabledIdleTimeoutMustNotReclaimAnAliveSession() {
        properties.setIdleSessionTimeout(null);
        advance(Duration.ofHours(6));

        manager.maintainSessionLeases();

        verify(processRunner, never()).stop(any());
        assertTrue(manager.isRunning(11L));
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    /** 启动一个处于运行态的会话，作为各用例的公共前置。 */
    private ProcessFixture startRunningSession() {
        Process process = mock(Process.class);
        AtomicBoolean alive = new AtomicBoolean(true);
        when(process.isAlive()).thenAnswer(invocation -> alive.get());
        when(process.onExit()).thenReturn(new CompletableFuture<>());
        DevServerProcessSession processSession = new DevServerProcessSession(
                project, 5180, process, CompletableFuture.completedFuture(null));
        when(projectLocator.locate(any(App.class))).thenReturn(project);
        when(portAllocator.reserve(11L, null)).thenReturn(5180);
        when(outputHub.sink(11L)).thenReturn(line -> { });
        when(dependencyInstaller.ensureInstalled(project))
                .thenReturn(DependencyInstallResult.success("ok"));
        when(processRunner.start(eq(project), eq(5180), eq(11L), any(), any()))
                .thenReturn(processSession);
        when(leaseCoordinator.claimStarting(11L, 7L, project, 5180))
                .thenReturn(com.rush.rushaicodemother.service.devserver.persistence
                        .DevServerSessionClaimResult.ACQUIRED);
        when(leaseCoordinator.markRunning(eq(11L), any(), any())).thenReturn(true);
        manager.startDevServer(App.builder()
                .id(11L)
                .userId(7L)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .build(), 7L);
        assertTrue(manager.isRunning(11L));
        return new ProcessFixture(processSession, alive);
    }

    private record ProcessFixture(DevServerProcessSession processSession, AtomicBoolean alive) {
    }
}
