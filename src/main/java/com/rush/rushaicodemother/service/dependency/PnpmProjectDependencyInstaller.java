package com.rush.rushaicodemother.service.dependency;

import com.rush.rushaicodemother.config.DependencyInstallProperties;
import com.rush.rushaicodemother.infrastructure.process.ProjectProcessTerminator;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** pnpm 项目依赖供给实现，只编排校验、有限重试、修复和项目级并发边界。 */
@Slf4j
@Service
public class PnpmProjectDependencyInstaller implements ProjectDependencyInstaller {

    private static final String COMPLETE_MESSAGE = "依赖已存在且完整，跳过安装";

    private final PnpmInstallCommandExecutor commandExecutor;
    private final NodeModulesIntegrityService integrityService;
    private final ProjectProcessTerminator processTerminator;
    private final DependencyInstallProperties properties;
    private final GenerationExecutionContextService executionContextService;
    private final NodeProjectDirectoryValidator projectDirectoryValidator;
    private final ReentrantLock[] projectLocks;

    @Autowired
    public PnpmProjectDependencyInstaller(
            PnpmInstallCommandExecutor commandExecutor,
            NodeModulesIntegrityService integrityService,
            ProjectProcessTerminator processTerminator,
            DependencyInstallProperties properties,
            GenerationExecutionContextService executionContextService,
            NodeProjectDirectoryValidator projectDirectoryValidator
    ) {
        this.commandExecutor = commandExecutor;
        this.integrityService = integrityService;
        this.processTerminator = processTerminator;
        this.properties = properties;
        this.executionContextService = executionContextService;
        this.projectDirectoryValidator = projectDirectoryValidator;
        this.projectLocks = createLocks(properties.getLockStripes());
    }

    @Override
    public DependencyInstallResult ensureInstalled(Path projectDirectory) {
        return ensureInstalled(projectDirectory, null);
    }

    @Override
    public DependencyInstallResult ensureInstalled(Path projectDirectory, String taskId) {
        NodeProjectDirectoryValidator.Validation validation = projectDirectoryValidator.validate(projectDirectory);
        if (!validation.valid()) {
            return DependencyInstallResult.failed(
                    DependencyInstallResult.Status.INVALID_PROJECT,
                    "",
                    validation.errorDetail()
            );
        }

        Path projectPath = validation.projectPath();
        ReentrantLock projectLock = lockFor(projectPath);
        try {
            projectLock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return interruptedResult("");
        }

        try {
            if (integrityService.isComplete(projectPath)) {
                log.info("项目依赖完整，跳过安装: project={}", projectPath);
                return DependencyInstallResult.success(COMPLETE_MESSAGE);
            }
            if (Thread.currentThread().isInterrupted()) {
                return interruptedResult("");
            }
            return installWithBoundedRetries(projectPath, taskId);
        } finally {
            projectLock.unlock();
        }
    }

    @Override
    public boolean cancel(Path projectDirectory) {
        return projectDirectory != null && commandExecutor.cancel(projectDirectory);
    }

    private DependencyInstallResult installWithBoundedRetries(Path projectPath, String taskId) {
        StringBuilder combinedOutput = new StringBuilder();
        DependencyInstallResult lastResult = null;
        boolean force = false;

        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                return interruptedResult(limitOutput(combinedOutput.toString()));
            }

            log.info("开始安装项目依赖: project={}, attempt={}/{}, force={}",
                    projectPath, attempt, properties.getMaxAttempts(), force);
            Duration attemptTimeout = executionContextService.clampTimeout(taskId, properties.getCommandTimeout());
            BooleanSupplier cancellationRequested = () -> executionContextService.shouldStop(taskId);
            DependencyInstallResult installResult = commandExecutor.install(
                    projectPath, force, attemptTimeout, cancellationRequested);
            executionContextService.assertCanContinue(taskId);
            appendAttemptOutput(combinedOutput, attempt, installResult.output());
            lastResult = installResult;

            if (installResult.terminal()) {
                return withCombinedOutput(installResult, combinedOutput);
            }

            if (installResult.success()) {
                if (integrityService.isComplete(projectPath)) {
                    log.info("项目依赖安装并校验成功: project={}, attempt={}", projectPath, attempt);
                    return DependencyInstallResult.success(limitOutput(combinedOutput.toString()));
                }
                if (Thread.currentThread().isInterrupted()) {
                    return interruptedResult(limitOutput(combinedOutput.toString()));
                }
                if (attempt == properties.getMaxAttempts()) {
                    return DependencyInstallResult.failed(
                            DependencyInstallResult.Status.INTEGRITY_FAILED,
                            limitOutput(combinedOutput.toString()),
                            "pnpm install 已成功，但依赖完整性校验在 " + attempt + " 次尝试后仍未通过"
                    );
                }
                DependencyInstallResult cleanupFailure = cleanCorruptedPackages(projectPath, combinedOutput);
                if (cleanupFailure != null) {
                    return cleanupFailure;
                }
                force = true;
                continue;
            }

            if (attempt == properties.getMaxAttempts()) {
                break;
            }
            if (isPermissionError(installResult)) {
                int terminatedCount = processTerminator.terminateProjectProcesses(projectPath);
                log.warn("依赖安装遇到权限错误，已清理当前项目相关进程后重试: project={}, count={}",
                        projectPath, terminatedCount);
            } else {
                log.warn("依赖安装失败，将执行有限强制重试: project={}, attempt={}, status={}",
                        projectPath, attempt, installResult.status());
            }
            force = true;
        }

        if (lastResult == null) {
            return DependencyInstallResult.failed(
                    DependencyInstallResult.Status.FAILED,
                    limitOutput(combinedOutput.toString()),
                    "未执行依赖安装命令"
            );
        }
        return withCombinedOutput(lastResult, combinedOutput);
    }

    private DependencyInstallResult cleanCorruptedPackages(Path projectPath, StringBuilder combinedOutput) {
        try {
            integrityService.cleanCorruptedNativePackages(projectPath);
            return null;
        } catch (IOException exception) {
            log.error("清理损坏的原生依赖失败: project={}, exceptionType={}",
                    projectPath, exception.getClass().getName());
            return DependencyInstallResult.failed(
                    DependencyInstallResult.Status.INTEGRITY_FAILED,
                    limitOutput(combinedOutput.toString()),
                    "清理损坏依赖失败，请检查项目目录权限和文件占用情况"
            );
        }
    }

    private DependencyInstallResult withCombinedOutput(
            DependencyInstallResult result,
            StringBuilder combinedOutput
    ) {
        String output = limitOutput(combinedOutput.toString());
        return result.success()
                ? DependencyInstallResult.success(output)
                : DependencyInstallResult.failed(result.status(), output, result.errorDetail());
    }

    private void appendAttemptOutput(StringBuilder combinedOutput, int attempt, String output) {
        if (!combinedOutput.isEmpty()) {
            combinedOutput.append('\n');
        }
        combinedOutput.append("[安装尝试 ").append(attempt).append("]\n");
        if (output == null || output.isBlank()) {
            combinedOutput.append("(无输出)");
        } else {
            combinedOutput.append(output.strip());
        }
        int overflow = combinedOutput.length() - properties.getMaxOutputLength();
        if (overflow > 0) {
            combinedOutput.delete(0, overflow);
        }
    }

    private String limitOutput(String output) {
        if (output == null) {
            return "";
        }
        int maxLength = properties.getMaxOutputLength();
        return output.length() <= maxLength
                ? output
                : output.substring(output.length() - maxLength);
    }

    private boolean isPermissionError(DependencyInstallResult result) {
        String diagnostic = (result.output() + "\n" + result.errorDetail()).toUpperCase(Locale.ROOT);
        return diagnostic.contains("EPERM") || diagnostic.contains("OPERATION NOT PERMITTED");
    }

    private DependencyInstallResult interruptedResult(String output) {
        return DependencyInstallResult.failed(
                DependencyInstallResult.Status.INTERRUPTED,
                output,
                "依赖安装线程被中断"
        );
    }

    private ReentrantLock lockFor(Path projectPath) {
        return projectLocks[Math.floorMod(projectPath.toString().hashCode(), projectLocks.length)];
    }

    private ReentrantLock[] createLocks(int stripeCount) {
        if (stripeCount <= 0) {
            throw new IllegalArgumentException("项目安装锁条带数必须大于 0");
        }
        ReentrantLock[] locks = new ReentrantLock[stripeCount];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

}
