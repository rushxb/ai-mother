package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.process.GoProcessEnvironment;
import com.rush.rushaicodemother.infrastructure.process.GoToolchain;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessLifecycle;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessOutputLogPolicy;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessResult;
import com.rush.rushaicodemother.infrastructure.process.ProjectProcessTerminator;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxNetworkPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 使用统一进程边界在一次性工作区副本中运行生成的 Go 后端。 */
@Slf4j
@Component
public class ManagedGenerationBenchmarkBackendRuntime implements GenerationBenchmarkBackendRuntime {

    private static final String DISPLAY_COMMAND = "go run -mod=readonly ./cmd/server";

    private final GenerationBenchmarkBackendProperties properties;
    private final GenerationBenchmarkBackendPortAllocator portAllocator;
    private final GenerationBenchmarkBackendHttpProbe httpProbe;
    private final ManagedProcessExecutor processExecutor;
    private final ProjectProcessTerminator processTerminator;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final GoToolchain goToolchain;

    /**
 * 创建{@code Managed}生成基准测试后端运行时实例并完成必要的依赖和初始状态设置。
 *
 * @param properties 配置属性
 * @param portAllocator {@code portAllocator} 对应的调用参数
 * @param httpProbe {@code httpProbe} 对应的调用参数
 * @param processExecutor 进程执行器
 * @param processTerminator {@code processTerminator} 对应的调用参数
 * @param workspaceFileSystemService 处理该职责的领域服务
 * @param goToolchain {@code goToolchain} 对应的调用参数
 */
    public ManagedGenerationBenchmarkBackendRuntime(
            GenerationBenchmarkBackendProperties properties,
            GenerationBenchmarkBackendPortAllocator portAllocator,
            GenerationBenchmarkBackendHttpProbe httpProbe,
            ManagedProcessExecutor processExecutor,
            ProjectProcessTerminator processTerminator,
            WorkspaceFileSystemService workspaceFileSystemService,
            GoToolchain goToolchain
    ) {
        this.properties = properties;
        this.portAllocator = portAllocator;
        this.httpProbe = httpProbe;
        this.processExecutor = processExecutor;
        this.processTerminator = processTerminator;
        this.workspaceFileSystemService = workspaceFileSystemService;
        this.goToolchain = goToolchain;
    }

    /**
 * 启动{@code Managed}生成基准测试后端运行时。
 *
 * @param backendProjectDirectory 后端项目目录
 * @return {@code Managed}生成基准测试后端运行时
 */
    @Override
    public BackendRuntimeHandle start(Path backendProjectDirectory) {
        Path stagedProject = null;
        GenerationBenchmarkBackendPortAllocator.PortLease portLease = null;
        RuntimeCleanup cleanup = null;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            stagedProject = stageProject(backendProjectDirectory);
            portLease = portAllocator.reserve();
            AtomicBoolean cancellationRequested = new AtomicBoolean(false);
            AtomicReference<Process> processReference = new AtomicReference<>();
            CompletableFuture<Process> processStarted = new CompletableFuture<>();
            CompletableFuture<ManagedProcessResult> processCompletion = new CompletableFuture<>();
            cleanup = new RuntimeCleanup(
                    stagedProject,
                    portLease,
                    cancellationRequested,
                    processReference,
                    processCompletion
            );

            ManagedProcessRequest request = buildRequest(
                    stagedProject,
                    portLease.port(),
                    cancellationRequested,
                    processReference,
                    processStarted
            );
            portLease.releaseBindingForProcessStart();
            startProcess(request, processCompletion);
            Process process = awaitProcessStart(processStarted, processCompletion);
            if (process == null) {
                BackendRuntimeObservation observation = launchFailure(processCompletion);
                cleanup.close();
                return BackendRuntimeHandle.failed(observation);
            }
            BackendRuntimeObservation observation = httpProbe.awaitHealthy(process, portLease.port());
            if (!observation.passedValidation()) {
                cleanup.close();
                return BackendRuntimeHandle.failed(observation);
            }
            RuntimeCleanup ownedCleanup = cleanup;
            cleanup = null;
            return new BackendRuntimeHandle(
                    portLease.port(),
                    observation,
                    process::isAlive,
                    ownedCleanup::close
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            closePartial(cleanup, stagedProject, portLease);
            throw new IllegalStateException("等待后端运行时启动被中断", exception);
        } catch (RuntimeException exception) {
            closePartial(cleanup, stagedProject, portLease);
            if (Thread.currentThread().isInterrupted()) {
                throw exception;
            }
            log.warn("后端运行时准备失败: error={}", LogExceptionSanitizer.sanitizeMessage(exception));
            return BackendRuntimeHandle.failed(
                    BackendRuntimeObservation.failed("backend_runtime_setup_failed")
            );
        } catch (IOException exception) {
            closePartial(cleanup, stagedProject, portLease);
            log.warn("后端运行时准备失败: error={}", LogExceptionSanitizer.sanitizeMessage(exception));
            return BackendRuntimeHandle.failed(
                    BackendRuntimeObservation.failed("backend_runtime_setup_failed")
            );
        }
    }

    /** 返回阶段项目。 */
    private Path stageProject(Path source) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("后端项目目录不能为空");
        }
        Path normalizedSource = source.toAbsolutePath().normalize();
        workspaceFileSystemService.resolveExistingRegularFile(normalizedSource, "go.mod");
        workspaceFileSystemService.resolveExistingRegularFile(
                normalizedSource,
                "cmd/server/main.go"
        );
        Path runtimeRoot = workspaceFileSystemService.ensureDirectory(
                properties.getWorkspaceRoot().toAbsolutePath().normalize()
        );
        Path target = runtimeRoot.resolve("backend-" + UUID.randomUUID()).normalize();
        return workspaceFileSystemService.copyDirectory(normalizedSource, target).targetDirectory();
    }

    /** 构建并返回请求。 */
    private ManagedProcessRequest buildRequest(
            Path stagedProject,
            int port,
            AtomicBoolean cancellationRequested,
            AtomicReference<Process> processReference,
            CompletableFuture<Process> processStarted
    ) {
        Map<String, String> environment = new LinkedHashMap<>(GoProcessEnvironment.overrides());
        environment.put("SERVER_ADDR", "127.0.0.1:" + port);
        environment.put(
                "DATABASE_DSN",
                "file:benchmark-" + UUID.randomUUID() + "?mode=memory&cache=shared"
        );
        environment.put("LOG_LEVEL", "warn");
        return ManagedProcessRequest.builder()
                .workingDirectory(stagedProject)
                .command(List.of(
                        goToolchain.goExecutable(),
                        "run",
                        "-mod=readonly",
                        "./cmd/server"
                ))
                .displayCommand(DISPLAY_COMMAND)
                .environment(Map.copyOf(environment))
                .environmentVariablesToRemove(GoProcessEnvironment.variablesToRemove())
                .timeout(properties.getProcessTimeout())
                .heartbeatInterval(properties.getHeartbeatInterval())
                .outputDrainTimeout(properties.getOutputDrainTimeout())
                .maxOutputLength(properties.getMaxOutputLength())
                .redirectErrorStream(true)
                .outputLogPolicy(ManagedProcessOutputLogPolicy.SUMMARY)
                .logCategory("benchmark-backend-runtime")
                .logContext("port=" + port)
                .cancellationRequested(cancellationRequested::get)
                .lifecycle(new ManagedProcessLifecycle() {
                    /**
 * 响应已启动事件。
 *
 * @param process 进程
 */
                    @Override
                    public void onStarted(Process process) {
                        processReference.set(process);
                        processStarted.complete(process);
                        if (cancellationRequested.get()) {
                            processTerminator.terminate(process);
                        }
                    }
                })
                .networkPolicy(SandboxNetworkPolicy.NONE)
                .exposedPort(port)
                .build();
    }

    /** 启动进程。 */
    private void startProcess(
            ManagedProcessRequest request,
            CompletableFuture<ManagedProcessResult> completion
    ) {
        Thread.ofVirtual()
                .name("benchmark-backend-runtime-" + request.exposedPort())
                .start(() -> {
                    try {
                        completion.complete(processExecutor.execute(request));
                    } catch (Throwable failure) {
                        completion.completeExceptionally(failure);
                    }
                });
    }

    /** 等待进程开始完成。 */
    private Process awaitProcessStart(
            CompletableFuture<Process> processStarted,
            CompletableFuture<ManagedProcessResult> processCompletion
    ) throws InterruptedException {
        try {
            CompletableFuture.anyOf(processStarted, processCompletion)
                    .get(properties.getStartupTimeout().toNanos(), TimeUnit.NANOSECONDS);
            return processStarted.getNow(null);
        } catch (TimeoutException exception) {
            return null;
        } catch (ExecutionException exception) {
            return null;
        }
    }

    /** 返回{@code launch}失败。 */
    private BackendRuntimeObservation launchFailure(
            CompletableFuture<ManagedProcessResult> processCompletion
    ) {
        if (!processCompletion.isDone()) {
            return BackendRuntimeObservation.failed("backend_startup_timeout");
        }
        try {
            ManagedProcessResult result = processCompletion.getNow(null);
            if (result != null && result.status() == ManagedProcessResult.Status.START_FAILED) {
                return BackendRuntimeObservation.failed("backend_process_start_failed");
            }
            return BackendRuntimeObservation.failed("backend_process_exited");
        } catch (RuntimeException exception) {
            return BackendRuntimeObservation.failed("backend_process_start_failed");
        }
    }

    /** 关闭部分并释放资源。 */
    private void closePartial(
            RuntimeCleanup cleanup,
            Path stagedProject,
            GenerationBenchmarkBackendPortAllocator.PortLease portLease
    ) {
        if (cleanup != null) {
            cleanup.close();
            return;
        }
        if (portLease != null) {
            portLease.close();
        }
        deleteStagedProject(stagedProject);
    }

    /** 删除{@code Staged}项目。 */
    private void deleteStagedProject(Path stagedProject) {
        if (stagedProject == null) {
            return;
        }
        try {
            workspaceFileSystemService.deleteDirectory(stagedProject);
        } catch (IOException | RuntimeException exception) {
            log.warn("清理后端运行时副本失败: error={}",
                    LogExceptionSanitizer.sanitizeMessage(exception));
        }
    }

    private final class RuntimeCleanup {

        private final Path stagedProject;
        private final GenerationBenchmarkBackendPortAllocator.PortLease portLease;
        private final AtomicBoolean cancellationRequested;
        private final AtomicReference<Process> processReference;
        private final CompletableFuture<ManagedProcessResult> processCompletion;
        private final AtomicBoolean cleanupStarted = new AtomicBoolean(false);
        private final AtomicBoolean projectDeleted = new AtomicBoolean(false);

        private RuntimeCleanup(
                Path stagedProject,
                GenerationBenchmarkBackendPortAllocator.PortLease portLease,
                AtomicBoolean cancellationRequested,
                AtomicReference<Process> processReference,
                CompletableFuture<ManagedProcessResult> processCompletion
        ) {
            this.stagedProject = stagedProject;
            this.portLease = portLease;
            this.cancellationRequested = cancellationRequested;
            this.processReference = processReference;
            this.processCompletion = processCompletion;
        }

        /** 关闭运行时{@code Cleanup}并释放资源。 */
        private void close() {
            if (!cleanupStarted.compareAndSet(false, true)) {
                return;
            }
            cancellationRequested.set(true);
            Process process = processReference.get();
            if (process != null) {
                try {
                    processTerminator.terminate(process);
                } catch (RuntimeException exception) {
                    log.warn("终止后端运行时进程失败: error={}",
                            LogExceptionSanitizer.sanitizeMessage(exception));
                }
            }
            boolean completed = awaitCompletion();
            portLease.close();
            if (completed) {
                deleteProjectOnce();
            } else {
                processCompletion.whenComplete((ignored, failure) -> deleteProjectOnce());
            }
        }

        /** 等待异步执行完成并返回最终结果。 */
        private boolean awaitCompletion() {
            boolean interrupted = false;
            try {
                processCompletion.get(
                        properties.getShutdownTimeout().toNanos(),
                        TimeUnit.NANOSECONDS
                );
                return true;
            } catch (InterruptedException exception) {
                interrupted = true;
                return false;
            } catch (ExecutionException exception) {
                return true;
            } catch (TimeoutException exception) {
                log.warn("等待后端运行时进程清理超时");
                return false;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void deleteProjectOnce() {
            if (projectDeleted.compareAndSet(false, true)) {
                deleteStagedProject(stagedProject);
            }
        }
    }
}
