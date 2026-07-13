package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.dependency.DependencyInstallResult;
import com.rush.rushaicodemother.service.dependency.ProjectDependencyInstaller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevServerManagerTest {

    private Path tempDirectory;

    private DevServerRuntimeProperties properties;
    private ProjectDependencyInstaller dependencyInstaller;
    private DevServerProjectLocator projectLocator;
    private DevServerPortAllocator portAllocator;
    private VisualEditorBootstrapInjector bootstrapInjector;
    private DevServerProcessRunner processRunner;
    private DevServerOutputHub outputHub;
    private DevServerManager manager;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        tempDirectory = DevServerTestWorkspace.create("manager");
        properties = new DevServerRuntimeProperties();
        properties.setStopTimeout(Duration.ofMillis(200));
        dependencyInstaller = mock(ProjectDependencyInstaller.class);
        projectLocator = mock(DevServerProjectLocator.class);
        portAllocator = mock(DevServerPortAllocator.class);
        bootstrapInjector = mock(VisualEditorBootstrapInjector.class);
        processRunner = mock(DevServerProcessRunner.class);
        outputHub = mock(DevServerOutputHub.class);
        manager = new DevServerManager(
                properties,
                dependencyInstaller,
                projectLocator,
                portAllocator,
                bootstrapInjector,
                processRunner,
                outputHub
        );
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        DevServerTestWorkspace.delete(tempDirectory);
    }

    @Test
    void shouldStartOnceAndReportOwnershipWhenReusingRunningSession() {
        Path project = tempDirectory.resolve("project-11");
        ProcessFixture fixture = processFixture();
        stubSuccessfulStart(11L, project, 5180, fixture);
        App app = app(11L, 7L, CodeGenTypeEnum.VUE_PROJECT);

        DevServerStartResult first = manager.startDevServer(app, 7L);
        DevServerStartResult second = manager.startDevServer(app, 7L);

        assertEquals(new DevServerStartResult(5180, true), first);
        assertEquals(new DevServerStartResult(5180, false), second);
        assertTrue(manager.isRunning(11L));
        assertEquals(5180, manager.getPort(11L));
        verify(dependencyInstaller, times(1)).ensureInstalled(project);
        verify(processRunner, times(1)).start(eq(project), eq(5180), eq(11L), any(), any());
        verify(bootstrapInjector, times(2)).inject(project);
    }

    @Test
    void concurrentStartForSameApplicationMustCreateOnlyOneStartupTask() throws Exception {
        Path project = tempDirectory.resolve("project-11");
        CountDownLatch installEntered = new CountDownLatch(1);
        CountDownLatch releaseInstall = new CountDownLatch(1);
        ProcessFixture fixture = processFixture();
        when(projectLocator.locate(any(App.class))).thenReturn(project);
        when(portAllocator.reserve(11L, null)).thenReturn(5180);
        when(outputHub.sink(11L)).thenReturn(line -> { });
        when(dependencyInstaller.ensureInstalled(project)).thenAnswer(invocation -> {
            installEntered.countDown();
            releaseInstall.await(1, TimeUnit.SECONDS);
            return DependencyInstallResult.success("ok");
        });
        when(processRunner.start(eq(project), eq(5180), eq(11L), any(), any()))
                .thenReturn(fixture.session(project, 5180));
        App app = app(11L, 7L, CodeGenTypeEnum.VUE_PROJECT);

        Future<DevServerStartResult> first = executor.submit(() -> manager.startDevServer(app, 7L));
        assertTrue(installEntered.await(1, TimeUnit.SECONDS));

        assertThrows(BusinessException.class, () -> manager.startDevServer(app, 7L));
        releaseInstall.countDown();
        assertTrue(first.get(1, TimeUnit.SECONDS).startedByCaller());

        verify(dependencyInstaller, times(1)).ensureInstalled(project);
        verify(processRunner, times(1)).start(eq(project), eq(5180), eq(11L), any(), any());
    }

    @Test
    void startingSessionMustCountTowardPerUserQuota() throws Exception {
        properties.setMaxServersPerUser(1);
        Path firstProject = tempDirectory.resolve("project-11");
        Path secondProject = tempDirectory.resolve("project-12");
        CountDownLatch installEntered = new CountDownLatch(1);
        CountDownLatch releaseInstall = new CountDownLatch(1);
        ProcessFixture fixture = processFixture();
        when(projectLocator.locate(any(App.class))).thenAnswer(invocation -> {
            App value = invocation.getArgument(0);
            return value.getId().equals(11L) ? firstProject : secondProject;
        });
        when(portAllocator.reserve(11L, null)).thenReturn(5180);
        when(outputHub.sink(11L)).thenReturn(line -> { });
        when(dependencyInstaller.ensureInstalled(firstProject)).thenAnswer(invocation -> {
            installEntered.countDown();
            releaseInstall.await(1, TimeUnit.SECONDS);
            return DependencyInstallResult.success("ok");
        });
        when(processRunner.start(eq(firstProject), eq(5180), eq(11L), any(), any()))
                .thenReturn(fixture.session(firstProject, 5180));

        Future<DevServerStartResult> first = executor.submit(
                () -> manager.startDevServer(app(11L, 7L, CodeGenTypeEnum.VUE_PROJECT), 7L)
        );
        assertTrue(installEntered.await(1, TimeUnit.SECONDS));

        BusinessException quotaFailure = assertThrows(
                BusinessException.class,
                () -> manager.startDevServer(app(12L, 7L, CodeGenTypeEnum.VUE_PROJECT), 7L)
        );
        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), quotaFailure.getCode());
        verify(portAllocator, never()).reserve(eq(12L), any());

        releaseInstall.countDown();
        assertTrue(first.get(1, TimeUnit.SECONDS).startedByCaller());
    }

    @Test
    void startupFailureMustReleaseSessionPortAndQuota() {
        Path project = tempDirectory.resolve("project-11");
        when(projectLocator.locate(any(App.class))).thenReturn(project);
        when(portAllocator.reserve(11L, null)).thenReturn(5180);
        when(outputHub.sink(11L)).thenReturn(line -> { });
        when(dependencyInstaller.ensureInstalled(project)).thenReturn(DependencyInstallResult.success("ok"));
        when(processRunner.start(eq(project), eq(5180), eq(11L), any(), any()))
                .thenThrow(new DevServerStartException(
                        DevServerStartException.Reason.STARTUP_TIMEOUT,
                        "timeout"
                ));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> manager.startDevServer(app(11L, 7L, CodeGenTypeEnum.VUE_PROJECT), 7L)
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertEquals("Dev Server 启动超时，请稍后重试", exception.getMessage());
        assertFalse(exception.getMessage().contains("timeout"));
        assertTrue(exception.getCause() instanceof DevServerStartException);
        assertFalse(manager.isRunning(11L));
        assertNull(manager.getPort(11L));
        verify(portAllocator).release(11L);
    }

    @Test
    void everyStructuredStartFailureMustMapToStablePublicContract() {
        List<StartFailureExpectation> expectations = List.of(
                new StartFailureExpectation(
                        DevServerStartException.Reason.INVALID_LAUNCHER,
                        ErrorCode.NOT_FOUND_ERROR,
                        "项目缺少可用的 Dev Server 启动器"
                ),
                new StartFailureExpectation(
                        DevServerStartException.Reason.DEPENDENCY_INSTALL_FAILED,
                        ErrorCode.SYSTEM_ERROR,
                        "项目依赖安装失败，请稍后重试"
                ),
                new StartFailureExpectation(
                        DevServerStartException.Reason.PROCESS_START_FAILED,
                        ErrorCode.SYSTEM_ERROR,
                        "Dev Server 启动失败，请稍后重试"
                ),
                new StartFailureExpectation(
                        DevServerStartException.Reason.PROCESS_EXITED,
                        ErrorCode.SYSTEM_ERROR,
                        "Dev Server 启动后异常退出"
                ),
                new StartFailureExpectation(
                        DevServerStartException.Reason.STARTUP_TIMEOUT,
                        ErrorCode.SYSTEM_ERROR,
                        "Dev Server 启动超时，请稍后重试"
                ),
                new StartFailureExpectation(
                        DevServerStartException.Reason.CANCELLED,
                        ErrorCode.OPERATION_ERROR,
                        "Dev Server 启动已取消"
                ),
                new StartFailureExpectation(
                        DevServerStartException.Reason.INTERRUPTED,
                        ErrorCode.OPERATION_ERROR,
                        "Dev Server 启动被中断"
                )
        );

        long appId = 100L;
        for (StartFailureExpectation expectation : expectations) {
            Path project = tempDirectory.resolve("project-" + appId);
            int port = Math.toIntExact(5100L + appId);
            DevServerStartException cause = new DevServerStartException(
                    expectation.reason(),
                    "internal-detail-" + expectation.reason()
            );
            when(projectLocator.locate(any(App.class))).thenReturn(project);
            when(portAllocator.reserve(appId, null)).thenReturn(port);
            when(outputHub.sink(appId)).thenReturn(line -> { });
            when(dependencyInstaller.ensureInstalled(project)).thenReturn(DependencyInstallResult.success("ok"));
            when(processRunner.start(eq(project), eq(port), eq(appId), any(), any())).thenThrow(cause);

            long currentAppId = appId;
            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> manager.startDevServer(
                            app(currentAppId, 7L, CodeGenTypeEnum.VUE_PROJECT),
                            7L
                    ),
                    expectation.reason().name()
            );

            assertEquals(expectation.errorCode().getCode(), exception.getCode(), expectation.reason().name());
            assertEquals(expectation.publicMessage(), exception.getMessage(), expectation.reason().name());
            assertFalse(exception.getMessage().contains("internal-detail"), expectation.reason().name());
            assertSame(cause, exception.getCause(), expectation.reason().name());
            appId++;
        }
    }

    @Test
    void dependencyInstallFailureMustExposeStableMessageAndPreserveStructuredCause() {
        Path project = tempDirectory.resolve("project-11");
        when(projectLocator.locate(any(App.class))).thenReturn(project);
        when(portAllocator.reserve(11L, null)).thenReturn(5180);
        when(outputHub.sink(11L)).thenReturn(line -> { });
        when(dependencyInstaller.ensureInstalled(project)).thenReturn(DependencyInstallResult.failed(
                DependencyInstallResult.Status.FAILED,
                "",
                "registry-token=secret-value"
        ));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> manager.startDevServer(app(11L, 7L, CodeGenTypeEnum.VUE_PROJECT), 7L)
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertEquals("项目依赖安装失败，请稍后重试", exception.getMessage());
        assertFalse(exception.getMessage().contains("secret-value"));
        assertTrue(exception.getCause() instanceof DevServerStartException startException
                && startException.reason() == DevServerStartException.Reason.DEPENDENCY_INSTALL_FAILED);
        verify(processRunner, never()).start(any(), anyInt(), anyLong(), any(), any());
        verify(portAllocator).release(11L);
    }

    @Test
    void stopDuringDependencyInstallMustCancelAndPreventProcessStart() throws Exception {
        Path project = tempDirectory.resolve("project-11");
        CountDownLatch installEntered = new CountDownLatch(1);
        CountDownLatch releaseInstall = new CountDownLatch(1);
        when(projectLocator.locate(any(App.class))).thenReturn(project);
        when(portAllocator.reserve(11L, null)).thenReturn(5180);
        when(outputHub.sink(11L)).thenReturn(line -> { });
        when(dependencyInstaller.ensureInstalled(project)).thenAnswer(invocation -> {
            installEntered.countDown();
            releaseInstall.await(1, TimeUnit.SECONDS);
            return DependencyInstallResult.success("ok");
        });
        when(dependencyInstaller.cancel(project)).thenAnswer(invocation -> {
            releaseInstall.countDown();
            return true;
        });

        Future<DevServerStartResult> startup = executor.submit(
                () -> manager.startDevServer(app(11L, 7L, CodeGenTypeEnum.VUE_PROJECT), 7L)
        );
        assertTrue(installEntered.await(1, TimeUnit.SECONDS));

        manager.stopDevServer(11L);

        assertThrows(ExecutionException.class, () -> startup.get(1, TimeUnit.SECONDS));
        verify(dependencyInstaller).cancel(project);
        verify(processRunner, never()).start(any(), anyInt(), anyLong(), any(), any());
        verify(portAllocator).release(11L);
    }

    @Test
    void stopDuringProcessStartupMustSignalCancellationSupplier() throws Exception {
        Path project = tempDirectory.resolve("project-11");
        CountDownLatch processStartEntered = new CountDownLatch(1);
        when(projectLocator.locate(any(App.class))).thenReturn(project);
        when(portAllocator.reserve(11L, null)).thenReturn(5180);
        when(outputHub.sink(11L)).thenReturn(line -> { });
        when(dependencyInstaller.ensureInstalled(project)).thenReturn(DependencyInstallResult.success("ok"));
        when(processRunner.start(eq(project), eq(5180), eq(11L), any(), any()))
                .thenAnswer(invocation -> {
                    BooleanSupplier cancellation = invocation.getArgument(4);
                    processStartEntered.countDown();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                    while (!cancellation.getAsBoolean() && System.nanoTime() < deadline) {
                        Thread.sleep(1);
                    }
                    if (!cancellation.getAsBoolean()) {
                        throw new AssertionError("Cancellation signal was not observed");
                    }
                    throw new DevServerStartException(
                            DevServerStartException.Reason.CANCELLED,
                            "cancelled"
                    );
                });

        Future<DevServerStartResult> startup = executor.submit(
                () -> manager.startDevServer(app(11L, 7L, CodeGenTypeEnum.VUE_PROJECT), 7L)
        );
        assertTrue(processStartEntered.await(1, TimeUnit.SECONDS));

        manager.stopDevServer(11L);

        assertThrows(ExecutionException.class, () -> startup.get(1, TimeUnit.SECONDS));
        verify(portAllocator).release(11L);
    }

    @Test
    void stopRunningSessionMustStopAttachedProcess() {
        Path project = tempDirectory.resolve("project-11");
        ProcessFixture fixture = processFixture();
        stubSuccessfulStart(11L, project, 5180, fixture);
        manager.startDevServer(app(11L, 7L, CodeGenTypeEnum.VUE_PROJECT), 7L);

        manager.stopDevServer(11L);

        verify(processRunner).stop(org.mockito.ArgumentMatchers.argThat(
                session -> session.process() == fixture.process() && session.port() == 5180
        ));
        verify(portAllocator).release(11L);
        assertFalse(manager.isRunning(11L));
    }

    @Test
    void naturalExitMustReleaseSessionAndPort() {
        Path project = tempDirectory.resolve("project-11");
        ProcessFixture fixture = processFixture();
        stubSuccessfulStart(11L, project, 5180, fixture);
        manager.startDevServer(app(11L, 7L, CodeGenTypeEnum.VUE_PROJECT), 7L);

        fixture.alive().set(false);
        fixture.exitFuture().complete(fixture.process());

        verify(processRunner, timeout(1000)).awaitOutput(org.mockito.ArgumentMatchers.argThat(
                session -> session.process() == fixture.process() && session.port() == 5180
        ));
        verify(portAllocator, timeout(1000)).release(11L);
        assertFalse(manager.isRunning(11L));
        assertNull(manager.getPort(11L));
    }

    @Test
    void stopTimeoutMustKeepStoppingSessionUntilStartupCompensationFinishes() throws Exception {
        properties.setStopTimeout(Duration.ofMillis(20));
        Path project = tempDirectory.resolve("project-11");
        CountDownLatch installEntered = new CountDownLatch(1);
        CountDownLatch releaseInstall = new CountDownLatch(1);
        when(projectLocator.locate(any(App.class))).thenReturn(project);
        when(portAllocator.reserve(11L, null)).thenReturn(5180);
        when(outputHub.sink(11L)).thenReturn(line -> { });
        when(dependencyInstaller.ensureInstalled(project)).thenAnswer(invocation -> {
            installEntered.countDown();
            releaseInstall.await(2, TimeUnit.SECONDS);
            return DependencyInstallResult.success("ok");
        });

        Future<DevServerStartResult> startup = executor.submit(
                () -> manager.startDevServer(app(11L, 7L, CodeGenTypeEnum.VUE_PROJECT), 7L)
        );
        assertTrue(installEntered.await(1, TimeUnit.SECONDS));

        assertThrows(BusinessException.class, () -> manager.stopDevServer(11L));
        verify(processRunner).terminateProjectProcesses(project);
        assertThrows(
                BusinessException.class,
                () -> manager.startDevServer(app(11L, 7L, CodeGenTypeEnum.VUE_PROJECT), 7L)
        );

        releaseInstall.countDown();
        assertThrows(ExecutionException.class, () -> startup.get(1, TimeUnit.SECONDS));
        verify(portAllocator, timeout(1000)).release(11L);
    }

    @Test
    void destroyMustReuseStopFlowAndClearRegistries() {
        Path project = tempDirectory.resolve("project-11");
        ProcessFixture fixture = processFixture();
        stubSuccessfulStart(11L, project, 5180, fixture);
        manager.startDevServer(app(11L, 7L, CodeGenTypeEnum.VUE_PROJECT), 7L);

        manager.destroy();

        verify(processRunner).stop(org.mockito.ArgumentMatchers.argThat(
                session -> session.process() == fixture.process() && session.port() == 5180
        ));
        verify(portAllocator).clear();
        verify(outputHub).clear();
        assertThrows(
                BusinessException.class,
                () -> manager.startDevServer(app(12L, 7L, CodeGenTypeEnum.VUE_PROJECT), 7L)
        );
    }

    private void stubSuccessfulStart(
            Long appId,
            Path project,
            int port,
            ProcessFixture fixture
    ) {
        when(projectLocator.locate(any(App.class))).thenReturn(project);
        when(portAllocator.reserve(appId, null)).thenReturn(port);
        when(outputHub.sink(appId)).thenReturn(line -> { });
        when(dependencyInstaller.ensureInstalled(project)).thenReturn(DependencyInstallResult.success("ok"));
        when(processRunner.start(eq(project), eq(port), eq(appId), any(), any()))
                .thenReturn(fixture.session(project, port));
    }

    private App app(Long appId, Long userId, CodeGenTypeEnum type) {
        return App.builder()
                .id(appId)
                .userId(userId)
                .codeGenType(type.getValue())
                .build();
    }

    private ProcessFixture processFixture() {
        Process process = mock(Process.class);
        AtomicBoolean alive = new AtomicBoolean(true);
        CompletableFuture<Process> exitFuture = new CompletableFuture<>();
        when(process.isAlive()).thenAnswer(invocation -> alive.get());
        when(process.onExit()).thenReturn(exitFuture);
        when(process.exitValue()).thenAnswer(invocation -> {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 0;
        });
        return new ProcessFixture(process, alive, exitFuture);
    }

    private record ProcessFixture(
            Process process,
            AtomicBoolean alive,
            CompletableFuture<Process> exitFuture
    ) {
        private DevServerProcessSession session(Path project, int port) {
            return new DevServerProcessSession(
                    project,
                    port,
                    process,
                    CompletableFuture.completedFuture(null)
            );
        }
    }

    private record StartFailureExpectation(
            DevServerStartException.Reason reason,
            ErrorCode errorCode,
            String publicMessage
    ) {
    }
}
