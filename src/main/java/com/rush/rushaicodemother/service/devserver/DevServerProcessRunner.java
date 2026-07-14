package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.infrastructure.process.NodeProcessEnvironment;
import com.rush.rushaicodemother.infrastructure.process.ProcessStarter;
import com.rush.rushaicodemother.infrastructure.process.ProjectProcessTerminator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
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

    @Autowired
    public DevServerProcessRunner(
            DevServerRuntimeProperties properties,
            ViteLauncherResolver launcherResolver,
            ProjectProcessTerminator processTerminator,
            LoopbackReadinessProbe readinessProbe
    ) {
        this(
                properties,
                launcherResolver,
                processTerminator,
                readinessProbe,
                ProcessBuilder::start
        );
    }

    DevServerProcessRunner(
            DevServerRuntimeProperties properties,
            ViteLauncherResolver launcherResolver,
            ProjectProcessTerminator processTerminator,
            LoopbackReadinessProbe readinessProbe,
            ProcessStarter processStarter
    ) {
        this.properties = properties;
        this.launcherResolver = launcherResolver;
        this.processTerminator = processTerminator;
        this.readinessProbe = readinessProbe;
        this.processStarter = processStarter;
        this.outputPump = new DevServerOutputPump(properties.getMaxOutputLineLength());
    }

    DevServerProcessSession start(
            Path projectDirectory,
            int port,
            Long appId,
            Consumer<String> outputConsumer,
            BooleanSupplier cancellationRequested
    ) {
        List<String> command = launcherResolver.resolve(projectDirectory, port);
        Process process = null;
        CompletableFuture<Void> outputCompletion = null;
        boolean interrupted = false;

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(projectDirectory.toFile());
            processBuilder.redirectErrorStream(true);
            configureEnvironment(processBuilder.environment());
            log.info("启动 Dev Server: appId={}, port={}, project={}, command={}",
                    appId, port, projectDirectory, command);

            process = processStarter.start(processBuilder);
            outputCompletion = outputPump.start(process, "appId=" + appId, outputConsumer);
            waitUntilReady(process, port, cancellationRequested);
            return new DevServerProcessSession(projectDirectory, port, process, outputCompletion);
        } catch (InterruptedException exception) {
            interrupted = true;
            cleanupFailedStart(process, outputCompletion);
            throw new DevServerStartException(
                    DevServerStartException.Reason.INTERRUPTED,
                    "Dev Server 启动等待被中断",
                    exception
            );
        } catch (DevServerStartException exception) {
            cleanupFailedStart(process, outputCompletion);
            throw exception;
        } catch (IOException exception) {
            cleanupFailedStart(process, outputCompletion);
            throw new DevServerStartException(
                    DevServerStartException.Reason.PROCESS_START_FAILED,
                    "无法创建 Dev Server 进程",
                    exception
            );
        } catch (RuntimeException exception) {
            cleanupFailedStart(process, outputCompletion);
            throw exception;
        } finally {
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
        }
    }

    void terminateProjectProcesses(Path projectDirectory) {
        processTerminator.terminateProjectProcesses(projectDirectory);
    }

    private void waitUntilReady(
            Process process,
            int port,
            BooleanSupplier cancellationRequested
    ) throws InterruptedException {
        long startedAtNanos = System.nanoTime();
        long timeoutNanos = properties.getStartupTimeout().toNanos();
        long pollNanos = properties.getReadinessPollInterval().toNanos();
        int consecutiveSuccesses = 0;

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
                        "Dev Server 未在 " + properties.getStartupTimeout() + " 内就绪"
                );
            }
            long sleepNanos = Math.min(pollNanos, remainingNanos);
            TimeUnit.NANOSECONDS.sleep(Math.max(1, sleepNanos));
        }
    }

    private void cleanupFailedStart(Process process, CompletableFuture<Void> outputCompletion) {
        if (process != null) {
            processTerminator.terminate(process);
        }
        DevServerOutputPump.awaitCompletionPreservingInterrupt(
                outputCompletion,
                properties.getOutputDrainTimeout()
        );
    }

    private void configureEnvironment(Map<String, String> environment) {
        NodeProcessEnvironment.variablesToRemove().forEach(environment::remove);
        environment.putAll(NodeProcessEnvironment.overrides(false));
    }
}
