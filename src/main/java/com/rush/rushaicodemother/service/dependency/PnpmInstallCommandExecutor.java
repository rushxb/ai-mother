package com.rush.rushaicodemother.service.dependency;

import com.rush.rushaicodemother.config.DependencyInstallProperties;
import com.rush.rushaicodemother.infrastructure.process.ProcessStarter;
import com.rush.rushaicodemother.infrastructure.process.ProcessOutputCollector;
import com.rush.rushaicodemother.infrastructure.process.ProjectProcessTerminator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** 负责单次 pnpm install 命令的启动、输出、超时、取消和进程树回收。 */
@Slf4j
@Component
public class PnpmInstallCommandExecutor {

    private static final Duration MAX_WAIT_POLL_INTERVAL = Duration.ofMillis(250);

    private final DependencyInstallProperties properties;
    private final ProjectProcessTerminator processTerminator;
    private final ProcessStarter processStarter;
    private final Map<Path, ActiveInstall> activeInstalls = new ConcurrentHashMap<>();
    private final boolean windows;

    @Autowired
    public PnpmInstallCommandExecutor(
            DependencyInstallProperties properties,
            ProjectProcessTerminator processTerminator
    ) {
        this(properties, processTerminator, ProcessBuilder::start, isWindowsOperatingSystem());
    }

    PnpmInstallCommandExecutor(
            DependencyInstallProperties properties,
            ProjectProcessTerminator processTerminator,
            ProcessStarter processStarter,
            boolean windows
    ) {
        this.properties = properties;
        this.processTerminator = processTerminator;
        this.processStarter = processStarter;
        this.windows = windows;
    }

    DependencyInstallResult install(Path projectDirectory, boolean force) {
        return install(projectDirectory, force, properties.getCommandTimeout(), () -> false);
    }

    DependencyInstallResult install(
            Path projectDirectory,
            boolean force,
            Duration commandTimeout,
            BooleanSupplier cancellationRequested
    ) {
        if (commandTimeout == null || commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new IllegalArgumentException("??????????? 0");
        }
        BooleanSupplier effectiveCancellation = cancellationRequested == null ? () -> false : cancellationRequested;
        Path projectPath = normalize(projectDirectory);
        List<String> command = buildCommand(force);
        Process process = null;
        ProcessOutputCollector outputCollector = null;
        CompletableFuture<Void> outputCompletion = null;
        ActiveInstall activeInstall = null;
        log.info("执行依赖安装: command={}, project={}", String.join(" ", command), projectPath);

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(projectPath.toFile());
            processBuilder.redirectErrorStream(true);
            configureEnvironment(processBuilder.environment());

            process = processStarter.start(processBuilder);
            activeInstall = new ActiveInstall(process);
            ActiveInstall existingInstall = activeInstalls.putIfAbsent(projectPath, activeInstall);
            if (existingInstall != null) {
                processTerminator.terminate(process);
                return DependencyInstallResult.failed(
                        DependencyInstallResult.Status.FAILED,
                        "",
                        "同一项目已有依赖安装进程"
                );
            }

            outputCollector = new ProcessOutputCollector(
                    "dependency-process",
                    projectPath.toString(),
                    properties.getMaxOutputLength()
            );
            outputCompletion = outputCollector.start(process);
            WaitOutcome waitOutcome = waitForProcess(
                    process, activeInstall, outputCollector, commandTimeout, effectiveCancellation);

            if (!waitOutcome.completed()) {
                processTerminator.terminate(process);
                ProcessOutputCollector.awaitCompletionPreservingInterrupt(
                        outputCompletion,
                        properties.getOutputDrainTimeout()
                );
                return DependencyInstallResult.failed(
                        waitOutcome.status(),
                        outputCollector.output(),
                        waitOutcome.errorDetail()
                );
            }

            ProcessOutputCollector.awaitCompletionPreservingInterrupt(
                    outputCompletion,
                    properties.getOutputDrainTimeout()
            );
            String output = outputCollector.output();
            if (activeInstall.cancelled()) {
                return DependencyInstallResult.failed(
                        DependencyInstallResult.Status.CANCELLED,
                        output,
                        "依赖安装已取消"
                );
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("依赖安装命令执行成功: project={}", projectPath);
                return DependencyInstallResult.success(output);
            }
            log.warn("依赖安装命令执行失败: project={}, exitCode={}", projectPath, exitCode);
            return DependencyInstallResult.failed(
                    DependencyInstallResult.Status.FAILED,
                    output,
                    "pnpm install 退出码: " + exitCode
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                processTerminator.terminate(process);
            }
            if (outputCompletion != null) {
                ProcessOutputCollector.awaitCompletionPreservingInterrupt(
                        outputCompletion,
                        properties.getOutputDrainTimeout()
                );
            }
            return DependencyInstallResult.failed(
                    DependencyInstallResult.Status.INTERRUPTED,
                    outputCollector == null ? "" : outputCollector.output(),
                    "依赖安装线程被中断"
            );
        } catch (IOException | RuntimeException exception) {
            if (process != null) {
                processTerminator.terminate(process);
            }
            if (outputCompletion != null) {
                ProcessOutputCollector.awaitCompletionPreservingInterrupt(
                        outputCompletion,
                        properties.getOutputDrainTimeout()
                );
            }
            log.error("启动或执行依赖安装失败: project={}, error={}", projectPath, exception.getMessage(), exception);
            return DependencyInstallResult.failed(
                    DependencyInstallResult.Status.FAILED,
                    outputCollector == null ? "" : outputCollector.output(),
                    "执行 pnpm install 异常: " + safeMessage(exception)
            );
        } finally {
            if (activeInstall != null) {
                activeInstalls.remove(projectPath, activeInstall);
            }
        }
    }

    boolean cancel(Path projectDirectory) {
        if (projectDirectory == null) {
            return false;
        }
        Path projectPath = normalize(projectDirectory);
        ActiveInstall activeInstall = activeInstalls.get(projectPath);
        if (activeInstall == null) {
            return false;
        }
        activeInstall.markCancelled();
        boolean terminated = processTerminator.terminate(activeInstall.process());
        if (terminated) {
            log.info("已取消项目依赖安装: project={}", projectPath);
        } else {
            log.warn("取消项目依赖安装时进程未完全退出: project={}, pid={}",
                    projectPath, activeInstall.process().pid());
        }
        return terminated;
    }

    List<String> buildCommand(boolean force) {
        List<String> command = new ArrayList<>(List.of(
                windows ? "pnpm.cmd" : "pnpm",
                "install",
                "--reporter=append-only",
                "--prefer-offline",
                "--config.confirmModulesPurge=false"
        ));
        if (force) {
            command.add("--force");
        }
        return List.copyOf(command);
    }

    private WaitOutcome waitForProcess(
            Process process,
            ActiveInstall activeInstall,
            ProcessOutputCollector outputCollector,
            Duration commandTimeout,
            BooleanSupplier cancellationRequested
    ) throws InterruptedException {
        long startedAt = System.nanoTime();
        long lastHeartbeatAt = startedAt;
        long commandTimeoutNanos = commandTimeout.toNanos();
        long idleTimeoutNanos = properties.getIdleTimeout().toNanos();
        long heartbeatNanos = properties.getHeartbeatInterval().toNanos();

        while (true) {
            if (cancellationRequested.getAsBoolean()) {
                return WaitOutcome.failed(
                        DependencyInstallResult.Status.CANCELLED,
                        "?????????????"
                );
            }
            if (activeInstall.cancelled()) {
                return WaitOutcome.failed(
                        DependencyInstallResult.Status.CANCELLED,
                        "依赖安装已取消"
                );
            }

            long now = System.nanoTime();
            long elapsedNanos = now - startedAt;
            long idleNanos = outputCollector.idleNanos(now);
            if (elapsedNanos >= commandTimeoutNanos) {
                log.warn("依赖安装总超时: elapsed={}s, idle={}s, tail={}",
                        TimeUnit.NANOSECONDS.toSeconds(elapsedNanos),
                        TimeUnit.NANOSECONDS.toSeconds(idleNanos),
                        outputCollector.tailForLog());
                return WaitOutcome.failed(
                        DependencyInstallResult.Status.TIMED_OUT,
                        "依赖安装超过总超时 " + properties.getCommandTimeout()
                );
            }
            if (idleNanos >= idleTimeoutNanos) {
                log.warn("依赖安装长时间无输出: elapsed={}s, idle={}s, tail={}",
                        TimeUnit.NANOSECONDS.toSeconds(elapsedNanos),
                        TimeUnit.NANOSECONDS.toSeconds(idleNanos),
                        outputCollector.tailForLog());
                return WaitOutcome.failed(
                        DependencyInstallResult.Status.IDLE_TIMED_OUT,
                        "依赖安装持续无输出超过 " + properties.getIdleTimeout()
                );
            }
            if (now - lastHeartbeatAt >= heartbeatNanos) {
                lastHeartbeatAt = now;
                log.info("依赖安装进行中: elapsed={}s, idle={}s, tail={}",
                        TimeUnit.NANOSECONDS.toSeconds(elapsedNanos),
                        TimeUnit.NANOSECONDS.toSeconds(idleNanos),
                        outputCollector.tailForLog());
            }

            long remainingTotal = commandTimeoutNanos - elapsedNanos;
            long remainingIdle = idleTimeoutNanos - idleNanos;
            long waitNanos = Math.min(
                    MAX_WAIT_POLL_INTERVAL.toNanos(),
                    Math.min(remainingTotal, remainingIdle)
            );
            long waitMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(Math.max(1, waitNanos)));
            if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                return WaitOutcome.completedSuccessfully();
            }
        }
    }

    private void configureEnvironment(Map<String, String> environment) {
        environment.put("NO_UPDATE_NOTIFIER", "1");
        environment.put("NPM_CONFIG_AUDIT", "false");
        environment.put("NPM_CONFIG_FUND", "false");
    }

    private Path normalize(Path projectDirectory) {
        if (projectDirectory == null) {
            throw new IllegalArgumentException("项目目录不能为空");
        }
        Path normalized = projectDirectory.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            return normalized;
        }
        try {
            return normalized.toRealPath();
        } catch (IOException exception) {
            return normalized;
        }
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    private static boolean isWindowsOperatingSystem() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("windows");
    }

    private record ActiveInstall(Process process, AtomicBoolean cancellationRequested) {

        private ActiveInstall(Process process) {
            this(process, new AtomicBoolean(false));
        }

        private boolean cancelled() {
            return cancellationRequested.get();
        }

        private void markCancelled() {
            cancellationRequested.set(true);
        }
    }

    private record WaitOutcome(
            boolean completed,
            DependencyInstallResult.Status status,
            String errorDetail
    ) {

        private static WaitOutcome completedSuccessfully() {
            return new WaitOutcome(true, DependencyInstallResult.Status.SUCCESS, null);
        }

        private static WaitOutcome failed(DependencyInstallResult.Status status, String errorDetail) {
            return new WaitOutcome(false, status, errorDetail);
        }
    }
}
