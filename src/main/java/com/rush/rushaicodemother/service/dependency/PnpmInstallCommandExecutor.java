package com.rush.rushaicodemother.service.dependency;

import com.rush.rushaicodemother.config.DependencyInstallProperties;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessLifecycle;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessResult;
import com.rush.rushaicodemother.infrastructure.process.NodeProcessEnvironment;
import com.rush.rushaicodemother.infrastructure.process.NodeToolchain;
import com.rush.rushaicodemother.infrastructure.process.ProjectProcessTerminator;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxNetworkPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** 负责单次 pnpm install 命令的启动、输出、超时、取消和进程树回收。 */
@Slf4j
@Component
public class PnpmInstallCommandExecutor {

    private final DependencyInstallProperties properties;
    private final ManagedProcessExecutor processExecutor;
    private final ProjectProcessTerminator processTerminator;
    private final NodeToolchain nodeToolchain;
    private final NodeProjectDirectoryValidator projectDirectoryValidator;
    private final Map<Path, ActiveInstall> activeInstalls = new ConcurrentHashMap<>();

    public PnpmInstallCommandExecutor(
            DependencyInstallProperties properties,
            ManagedProcessExecutor processExecutor,
            ProjectProcessTerminator processTerminator,
            NodeToolchain nodeToolchain,
            NodeProjectDirectoryValidator projectDirectoryValidator
    ) {
        this.properties = properties;
        this.processExecutor = processExecutor;
        this.processTerminator = processTerminator;
        this.nodeToolchain = nodeToolchain;
        this.projectDirectoryValidator = projectDirectoryValidator;
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
        return install(
                projectDirectory,
                force,
                DependencyInstallMode.REUSE_IF_VALID,
                commandTimeout,
                cancellationRequested
        );
    }

    /** 返回{@code install}。 */
    DependencyInstallResult install(
            Path projectDirectory,
            boolean force,
            DependencyInstallMode mode,
            Duration commandTimeout,
            BooleanSupplier cancellationRequested
    ) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (commandTimeout == null || commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new IllegalArgumentException("命令超时时间必须大于 0");
        }
        BooleanSupplier effectiveCancellation = cancellationRequested == null ? () -> false : cancellationRequested;
        NodeProjectDirectoryValidator.Validation validation = projectDirectoryValidator.validate(projectDirectory);
        if (!validation.valid()) {
            return DependencyInstallResult.failed(
                    DependencyInstallResult.Status.INVALID_PROJECT,
                    "",
                    validation.errorDetail()
            );
        }
        Path projectPath = validation.projectPath();
        List<String> command = buildCommand(force, mode);
        ActiveInstall activeInstall = new ActiveInstall(processTerminator);
        ActiveInstall existingInstall = activeInstalls.putIfAbsent(projectPath, activeInstall);
        if (existingInstall != null) {
            return DependencyInstallResult.failed(
                    DependencyInstallResult.Status.FAILED,
                    "",
                    "同一项目已有依赖安装进程"
            );
        }

        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            AtomicBoolean taskCancellationObserved = new AtomicBoolean(false);
            ManagedProcessResult processResult = processExecutor.execute(
                    ManagedProcessRequest.builder()
                            .workingDirectory(projectPath)
                            .command(command)
                            .environment(NodeProcessEnvironment.overrides(false))
                            .environmentVariablesToRemove(NodeProcessEnvironment.variablesToRemove())
                            .timeout(commandTimeout)
                            .idleTimeout(properties.getIdleTimeout())
                            .heartbeatInterval(properties.getHeartbeatInterval())
                            .outputDrainTimeout(properties.getOutputDrainTimeout())
                            .maxOutputLength(properties.getMaxOutputLength())
                            .redirectErrorStream(true)
                            .logCategory("dependency-process")
                            .logContext(projectPath.toString())
                            .cancellationRequested(() -> {
                                boolean taskCancelled = effectiveCancellation.getAsBoolean();
                                if (taskCancelled) {
                                    taskCancellationObserved.set(true);
                                }
                                return taskCancelled || activeInstall.cancelled();
                            })
                            .lifecycle(activeInstall)
                            .networkPolicy(SandboxNetworkPolicy.DEPENDENCY_EGRESS)
                            .build()
            );
            DependencyInstallResult result = toDependencyResult(
                    processResult,
                    activeInstall.cancelled() || taskCancellationObserved.get()
            );
            if (result.success()) {
                log.info("依赖安装命令执行成功: project={}", projectPath);
            } else {
                log.warn("依赖安装命令执行失败: project={}, status={}, exitCode={}",
                        projectPath, processResult.status(), processResult.exitCode());
            }
            return result;
        } finally {
            activeInstalls.remove(projectPath, activeInstall);
        }
    }

    /** 将当前对象转换为依赖结果。 */
    private DependencyInstallResult toDependencyResult(
            ManagedProcessResult processResult,
            boolean cancellationObserved
    ) {
        String output = processResult.combinedOutput();
        if (cancellationObserved) {
            return DependencyInstallResult.failed(
                    DependencyInstallResult.Status.CANCELLED,
                    output,
                    "依赖安装已取消"
            );
        }
        if (processResult.status() == ManagedProcessResult.Status.COMPLETED) {
            return Integer.valueOf(0).equals(processResult.exitCode())
                    ? DependencyInstallResult.success(output)
                    : DependencyInstallResult.failed(
                            DependencyInstallResult.Status.FAILED,
                            output,
                            "pnpm install 退出码: " + processResult.exitCode()
                    );
        }
        return DependencyInstallResult.failed(
                switch (processResult.status()) {
                    case TIMED_OUT -> DependencyInstallResult.Status.TIMED_OUT;
                    case IDLE_TIMED_OUT -> DependencyInstallResult.Status.IDLE_TIMED_OUT;
                    case INTERRUPTED -> DependencyInstallResult.Status.INTERRUPTED;
                    case START_FAILED -> DependencyInstallResult.Status.FAILED;
                    case COMPLETED -> throw new IllegalStateException("已完成进程必须包含退出码结果");
                },
                output,
                processResult.status() == ManagedProcessResult.Status.START_FAILED
                        ? "执行 pnpm install 失败，请检查 Node.js、pnpm 和项目配置"
                        : processResult.errorDetail()
        );
    }

    /** 取消{@code Pnpm}{@code Install}命令。 */
    boolean cancel(Path projectDirectory) {
        if (projectDirectory == null) {
            return false;
        }
        NodeProjectDirectoryValidator.Validation validation =
                projectDirectoryValidator.resolveProjectDirectory(projectDirectory);
        if (!validation.valid()) {
            return false;
        }
        Path projectPath = validation.projectPath();
        ActiveInstall activeInstall = activeInstalls.get(projectPath);
        if (activeInstall == null) {
            return false;
        }
        boolean terminated = activeInstall.cancel();
        if (terminated) {
            log.info("已取消项目依赖安装: project={}", projectPath);
        } else {
            log.warn("取消项目依赖安装时进程未完全退出: project={}", projectPath);
        }
        return terminated;
    }

    List<String> buildCommand(boolean force) {
        return buildCommand(force, DependencyInstallMode.REUSE_IF_VALID);
    }

    /** 构建并返回命令。 */
    List<String> buildCommand(boolean force, DependencyInstallMode mode) {
        List<String> command = new ArrayList<>(List.of(
                nodeToolchain.pnpmExecutable(),
                "install",
                "--reporter=append-only",
                "--prefer-offline",
                "--ignore-scripts",
                "--ignore-pnpmfile",
                "--config.confirmModulesPurge=false"
        ));
        DependencyInstallMode effectiveMode = mode == null
                ? DependencyInstallMode.REUSE_IF_VALID
                : mode;
        if (effectiveMode.frozenLockfile()) {
            command.add("--frozen-lockfile");
        } else {
            command.add("--no-frozen-lockfile");
        }
        if (force) {
            command.add("--force");
        }
        return List.copyOf(command);
    }

    private static final class ActiveInstall implements ManagedProcessLifecycle {

        private final ProjectProcessTerminator processTerminator;
        private final AtomicReference<Process> process = new AtomicReference<>();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);

        private ActiveInstall(ProjectProcessTerminator processTerminator) {
            this.processTerminator = processTerminator;
        }

        /**
 * 响应已启动事件。
 *
 * @param startedProcess 待处理的 {@code startedProcess} 集合
 */
        @Override
        public void onStarted(Process startedProcess) {
            process.set(startedProcess);
            if (cancelled()) {
                processTerminator.terminate(startedProcess);
            }
        }

        @Override
        public void onFinished(Process finishedProcess) {
            process.compareAndSet(finishedProcess, null);
        }

        private boolean cancelled() {
            return cancellationRequested.get();
        }

        private boolean cancel() {
            cancellationRequested.set(true);
            Process activeProcess = process.get();
            return activeProcess == null
                    || processTerminator.terminate(activeProcess)
                    || !activeProcess.isAlive();
        }
    }
}
