package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.infrastructure.process.NodeProcessEnvironment;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import com.rush.rushaicodemother.infrastructure.process.ProcessStarter;
import com.rush.rushaicodemother.infrastructure.process.ProjectProcessTerminator;
import com.rush.rushaicodemother.infrastructure.sandbox.GeneratedCodeProcessSandbox;
import com.rush.rushaicodemother.infrastructure.sandbox.HostLocalGeneratedCodeProcessSandbox;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxNetworkPolicy;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxProcessPlan;
import com.rush.rushaicodemother.monitor.GeneratedCodeSandboxMetricsCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Dev Server 进程运行模块。
 * 负责安全命令解析、启动就绪判定、取消、中断、输出消费和进程树回收。
 */
@Slf4j
@Component
public class DevServerProcessRunner {

    private static final int REQUIRED_CONSECUTIVE_READINESS_SUCCESSES = 2;

    private final DevServerRuntimeProperties properties;
    private final ViteLauncherResolver launcherResolver;
    private final ProjectProcessTerminator processTerminator;
    private final LoopbackReadinessProbe readinessProbe;
    private final ProcessStarter processStarter;
    private final DevServerOutputPump outputPump;
    private final GeneratedCodeProcessSandbox processSandbox;
    private final GeneratedCodeSandboxMetricsCollector sandboxMetrics;
    private final DevServerSandboxPlanListener sandboxPlanListener;

    /**
 * 创建开发服务器进程{@code Runner}实例并完成必要的依赖和初始状态设置。
 *
 * @param properties 配置属性
 * @param launcherResolver {@code launcherResolver} 对应的调用参数
 * @param processTerminator {@code processTerminator} 对应的调用参数
 * @param readinessProbe {@code readinessProbe} 对应的调用参数
 * @param processSandbox {@code processSandbox} 对应的调用参数
 * @param sandboxMetrics 待处理的 {@code sandboxMetrics} 集合
 * @param sandboxPlanListener 对应阶段使用的回调函数
 */
    @Autowired
    public DevServerProcessRunner(
            DevServerRuntimeProperties properties,
            ViteLauncherResolver launcherResolver,
            ProjectProcessTerminator processTerminator,
            LoopbackReadinessProbe readinessProbe,
            GeneratedCodeProcessSandbox processSandbox,
            GeneratedCodeSandboxMetricsCollector sandboxMetrics,
            DevServerSandboxPlanListener sandboxPlanListener
    ) {
        this(
                properties,
                launcherResolver,
                processTerminator,
                readinessProbe,
                ProcessBuilder::start,
                processSandbox,
                sandboxMetrics,
                sandboxPlanListener
        );
    }

    /** 创建开发服务器进程{@code Runner}实例并完成必要的依赖和初始状态设置。 */
    DevServerProcessRunner(
            DevServerRuntimeProperties properties,
            ViteLauncherResolver launcherResolver,
            ProjectProcessTerminator processTerminator,
            LoopbackReadinessProbe readinessProbe,
            ProcessStarter processStarter
    ) {
        this(
                properties,
                launcherResolver,
                processTerminator,
                readinessProbe,
                processStarter,
                new HostLocalGeneratedCodeProcessSandbox(),
                GeneratedCodeSandboxMetricsCollector.noOp(),
                DevServerSandboxPlanListener.noOp()
        );
    }

    /** 创建开发服务器进程{@code Runner}实例并完成必要的依赖和初始状态设置。 */
    DevServerProcessRunner(
            DevServerRuntimeProperties properties,
            ViteLauncherResolver launcherResolver,
            ProjectProcessTerminator processTerminator,
            LoopbackReadinessProbe readinessProbe,
            ProcessStarter processStarter,
            GeneratedCodeProcessSandbox processSandbox
    ) {
        this(
                properties,
                launcherResolver,
                processTerminator,
                readinessProbe,
                processStarter,
                processSandbox,
                GeneratedCodeSandboxMetricsCollector.noOp(),
                DevServerSandboxPlanListener.noOp()
        );
    }

    /** 创建开发服务器进程{@code Runner}实例并完成必要的依赖和初始状态设置。 */
    DevServerProcessRunner(
            DevServerRuntimeProperties properties,
            ViteLauncherResolver launcherResolver,
            ProjectProcessTerminator processTerminator,
            LoopbackReadinessProbe readinessProbe,
            ProcessStarter processStarter,
            GeneratedCodeProcessSandbox processSandbox,
            GeneratedCodeSandboxMetricsCollector sandboxMetrics
    ) {
        this(
                properties,
                launcherResolver,
                processTerminator,
                readinessProbe,
                processStarter,
                processSandbox,
                sandboxMetrics,
                DevServerSandboxPlanListener.noOp()
        );
    }

    DevServerProcessRunner(
            DevServerRuntimeProperties properties,
            ViteLauncherResolver launcherResolver,
            ProjectProcessTerminator processTerminator,
            LoopbackReadinessProbe readinessProbe,
            ProcessStarter processStarter,
            GeneratedCodeProcessSandbox processSandbox,
            GeneratedCodeSandboxMetricsCollector sandboxMetrics,
            DevServerSandboxPlanListener sandboxPlanListener
    ) {
        this.properties = properties;
        this.launcherResolver = launcherResolver;
        this.processTerminator = processTerminator;
        this.readinessProbe = readinessProbe;
        this.processStarter = processStarter;
        this.processSandbox = processSandbox;
        this.sandboxMetrics = sandboxMetrics;
        this.sandboxPlanListener = sandboxPlanListener;
        this.outputPump = new DevServerOutputPump(properties.getMaxOutputLineLength());
    }

    DevServerProcessSession start(
            Path projectDirectory,
            int port,
            Long appId,
            Consumer<String> outputConsumer,
            BooleanSupplier cancellationRequested
    ) {
        return start(
                projectDirectory,
                port,
                appId,
                outputConsumer,
                properties.getStartupTimeout(),
                cancellationRequested
        );
    }

    DevServerProcessSession start(
            Path projectDirectory,
            int port,
            Long appId,
            Consumer<String> outputConsumer,
            Duration startupTimeout,
            BooleanSupplier cancellationRequested
    ) {
        return start(
                projectDirectory,
                port,
                appId,
                outputConsumer,
                startupTimeout,
                cancellationRequested,
                Map.of()
        );
    }

    /** 启动开发服务器进程。 */
    DevServerProcessSession start(
            Path projectDirectory,
            int port,
            Long appId,
            Consumer<String> outputConsumer,
            Duration startupTimeout,
            BooleanSupplier cancellationRequested,
            Map<String, String> environmentOverrides
    ) {
        requirePositiveTimeout(startupTimeout);
        BooleanSupplier effectiveCancellation = cancellationRequested == null ? () -> false : cancellationRequested;
        List<String> command = launcherResolver.resolve(projectDirectory, port, appId);
        Path normalizedProjectDirectory = projectDirectory.toAbsolutePath().normalize();
        Process process = null;
        CompletableFuture<Void> outputCompletion = null;
        SandboxProcessPlan processPlan = null;
        String sandboxOutcome = "start_failed";
        long sandboxStartedAtNanos = System.nanoTime();
        boolean interrupted = false;

        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            ManagedProcessRequest request = ManagedProcessRequest.builder()
                    .workingDirectory(normalizedProjectDirectory)
                    .command(command)
                    .environment(processEnvironment(environmentOverrides))
                    .environmentVariablesToRemove(NodeProcessEnvironment.variablesToRemove())
                    .networkPolicy(SandboxNetworkPolicy.RUNTIME_INTERNAL)
                    .exposedPort(port)
                    .build();
            processPlan = processSandbox.prepareDevServer(request, normalizedProjectDirectory, port);
            sandboxPlanListener.onPlanPrepared(appId, processPlan);
            ProcessBuilder processBuilder = new ProcessBuilder(processPlan.hostCommand());
            processBuilder.directory(processPlan.hostWorkingDirectory().toFile());
            processBuilder.redirectErrorStream(true);
            processBuilder.environment().putAll(processPlan.hostEnvironment());
            processPlan.hostEnvironmentVariablesToRemove().forEach(processBuilder.environment()::remove);
            log.info("启动 Dev Server: appId={}, port={}, project={}, sandbox={}, networkPolicy={}",
                    appId, port, normalizedProjectDirectory, processPlan.backend(), request.networkPolicy());

            process = processStarter.start(processBuilder);
            outputCompletion = outputPump.start(process, "appId=" + appId, outputConsumer);
            processSandbox.activate(processPlan);
            waitUntilReady(process, port, startupTimeout, effectiveCancellation);
            sandboxOutcome = "ready";
            return new DevServerProcessSession(
                    normalizedProjectDirectory,
                    port,
                    process,
                    outputCompletion,
                    processPlan
            );
        } catch (InterruptedException exception) {
            sandboxOutcome = "interrupted";
            interrupted = true;
            cleanupFailedStart(process, outputCompletion, processPlan);
            throw new DevServerStartException(
                    DevServerStartException.Reason.INTERRUPTED,
                    "Dev Server 启动等待被中断",
                    exception
            );
        } catch (DevServerStartException exception) {
            sandboxOutcome = exception.reason().name();
            cleanupFailedStart(process, outputCompletion, processPlan);
            throw exception;
        } catch (IOException exception) {
            sandboxOutcome = "process_start_failed";
            cleanupFailedStart(process, outputCompletion, processPlan);
            throw new DevServerStartException(
                    DevServerStartException.Reason.PROCESS_START_FAILED,
                    "无法创建 Dev Server 进程",
                    exception
            );
        } catch (RuntimeException exception) {
            sandboxOutcome = "start_failed";
            cleanupFailedStart(process, outputCompletion, processPlan);
            throw exception;
        } finally {
            sandboxMetrics.recordExecution(
                    processPlan == null ? "unknown" : processPlan.backend(),
                    "dev-server",
                    sandboxOutcome,
                    Duration.ofNanos(Math.max(0, System.nanoTime() - sandboxStartedAtNanos))
            );
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void stop(DevServerProcessSession session) {
        if (session == null) {
            return;
        }
        processTerminator.terminate(session.process());
        awaitOutput(session);
    }

    void awaitOutput(DevServerProcessSession session) {
        if (session != null) {
            DevServerOutputPump.awaitCompletionPreservingInterrupt(
                    session.outputCompletion(),
                    properties.getOutputDrainTimeout()
            );
            cleanupSandbox(session);
        }
    }

    void terminateProjectProcesses(Path projectDirectory) {
        processTerminator.terminateProjectProcesses(projectDirectory);
    }

    /** 处理{@code wait}{@code Until}就绪。 */
    private void waitUntilReady(
            Process process,
            int port,
            Duration startupTimeout,
            BooleanSupplier cancellationRequested
    ) throws InterruptedException {
        long startedAtNanos = System.nanoTime();
        long timeoutNanos = startupTimeout.toNanos();
        long pollNanos = properties.getReadinessPollInterval().toNanos();
        int consecutiveSuccesses = 0;

        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        while (true) {
            if (cancellationRequested.getAsBoolean()) {
                throw new DevServerStartException(
                        DevServerStartException.Reason.CANCELLED,
                        "Dev Server 启动已取消"
                );
            }
            if (!process.isAlive()) {
                throw new DevServerStartException(
                        DevServerStartException.Reason.PROCESS_EXITED,
                        "Dev Server 启动失败，进程已退出，exitCode=" + process.exitValue()
                );
            }
            if (readinessProbe.isReady(port)) {
                consecutiveSuccesses++;
                if (consecutiveSuccesses >= REQUIRED_CONSECUTIVE_READINESS_SUCCESSES) {
                    log.info("Dev Server 端口已就绪: port={}", port);
                    return;
                }
            } else {
                consecutiveSuccesses = 0;
            }

            long elapsedNanos = System.nanoTime() - startedAtNanos;
            long remainingNanos = timeoutNanos - elapsedNanos;
            if (remainingNanos <= 0) {
                throw new DevServerStartException(
                        DevServerStartException.Reason.STARTUP_TIMEOUT,
                        "Dev Server 未在 " + startupTimeout + " 内就绪"
                );
            }
            long sleepNanos = Math.min(pollNanos, remainingNanos);
            TimeUnit.NANOSECONDS.sleep(Math.max(1, sleepNanos));
        }
    }

    private void requirePositiveTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Dev Server startup timeout must be greater than zero");
        }
    }

    private Map<String, String> processEnvironment(Map<String, String> environmentOverrides) {
        Map<String, String> environment = new LinkedHashMap<>(
                NodeProcessEnvironment.overrides(false)
        );
        if (environmentOverrides != null) {
            environment.putAll(environmentOverrides);
        }
        return Map.copyOf(environment);
    }

    private void cleanupFailedStart(
            Process process,
            CompletableFuture<Void> outputCompletion,
            SandboxProcessPlan processPlan
    ) {
        if (process != null) {
            processTerminator.terminate(process);
        }
        DevServerOutputPump.awaitCompletionPreservingInterrupt(
                outputCompletion,
                properties.getOutputDrainTimeout()
        );
        cleanupSandbox(processPlan);
    }

    private void cleanupSandbox(DevServerProcessSession session) {
        if (session.beginSandboxCleanup()) {
            cleanupSandbox(session.sandboxPlan());
        }
    }

    /** 清理{@code Sandbox}及其关联资源。 */
    private void cleanupSandbox(SandboxProcessPlan processPlan) {
        if (processPlan == null) {
            return;
        }
        try {
            processSandbox.cleanup(processPlan);
            if (!processPlan.cleanupResourceId().isBlank()) {
                sandboxMetrics.recordCleanup(processPlan.backend(), "success");
            }
        } catch (RuntimeException exception) {
            sandboxMetrics.recordCleanup(processPlan.backend(), "failure");
            log.warn("Dev Server Sandbox cleanup failed: backend={}, exceptionType={}",
                    processPlan.backend(), exception.getClass().getName());
        }
    }
}
