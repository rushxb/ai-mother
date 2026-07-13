package com.rush.rushaicodemother.infrastructure.process;

import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 项目脚本命令适配器，负责 pnpm 命令构造和项目命令结果语义。
 */
@Slf4j
@Component
public class ProjectCommandExecutor {

    private final ProjectCommandProperties properties;
    private final ManagedProcessExecutor processExecutor;
    private final GenerationExecutionContextService executionContextService;
    private final boolean windows;

    @Autowired
    public ProjectCommandExecutor(
            ProjectCommandProperties properties,
            ManagedProcessExecutor processExecutor,
            GenerationExecutionContextService executionContextService
    ) {
        this(properties, processExecutor, executionContextService, isWindowsOperatingSystem());
    }

    ProjectCommandExecutor(
            ProjectCommandProperties properties,
            ProjectProcessTerminator processTerminator,
            ProcessStarter processStarter,
            boolean windows
    ) {
        this(properties, new ManagedProcessExecutor(processTerminator, processStarter), null, windows);
    }

    ProjectCommandExecutor(
            ProjectCommandProperties properties,
            ManagedProcessExecutor processExecutor,
            boolean windows
    ) {
        this(properties, processExecutor, null, windows);
    }

    private ProjectCommandExecutor(
            ProjectCommandProperties properties,
            ManagedProcessExecutor processExecutor,
            GenerationExecutionContextService executionContextService,
            boolean windows
    ) {
        this.properties = properties;
        this.processExecutor = processExecutor;
        this.executionContextService = executionContextService;
        this.windows = windows;
    }

    public ProjectCommandResult executePnpmScript(
            Path projectDirectory,
            String scriptName,
            Duration commandTimeout,
            String logContext
    ) {
        return executePnpmScript(projectDirectory, scriptName, commandTimeout, null, logContext);
    }

    public ProjectCommandResult executePnpmScript(
            Path projectDirectory,
            String scriptName,
            Duration commandTimeout,
            String taskId,
            String logContext
    ) {
        if (scriptName == null || scriptName.isBlank()) {
            throw new IllegalArgumentException("????????");
        }
        Duration effectiveTimeout = executionContextService == null
                ? commandTimeout
                : executionContextService.clampTimeout(taskId, commandTimeout);
        ProjectCommandResult result = execute(
                projectDirectory,
                List.of(windows ? "pnpm.cmd" : "pnpm", "run", scriptName),
                effectiveTimeout,
                taskId,
                logContext
        );
        if (executionContextService != null) {
            executionContextService.assertCanContinue(taskId);
        }
        return result;
    }

    public ProjectCommandResult execute(
            Path projectDirectory,
            List<String> command,
            Duration commandTimeout,
            String logContext
    ) {
        return execute(projectDirectory, command, commandTimeout, null, logContext);
    }

    private ProjectCommandResult execute(
            Path projectDirectory,
            List<String> command,
            Duration commandTimeout,
            String taskId,
            String logContext
    ) {
        ManagedProcessResult managedResult = processExecutor.execute(
                ManagedProcessRequest.builder()
                        .workingDirectory(projectDirectory)
                        .command(command)
                        .environment(controlledPnpmEnvironment())
                        .timeout(commandTimeout)
                        .idleTimeout(properties.getIdleTimeout())
                        .heartbeatInterval(properties.getHeartbeatInterval())
                        .outputDrainTimeout(properties.getOutputDrainTimeout())
                        .maxOutputLength(properties.getMaxOutputLength())
                        .redirectErrorStream(true)
                        .logCategory("project-command")
                        .logContext(logContext)
                        .cancellationRequested(() -> executionContextService != null
                                && executionContextService.shouldStop(taskId))
                        .build()
        );
        ProjectCommandResult result = toProjectCommandResult(managedResult);
        if (result.success()) {
            log.info("????????: command={}", result.command());
        } else {
            log.warn("?????????: command={}, status={}, exitCode={}",
                    result.command(), result.status(), result.exitCode());
        }
        return result;
    }

    private ProjectCommandResult toProjectCommandResult(ManagedProcessResult result) {
        if (result.status() == ManagedProcessResult.Status.COMPLETED) {
            if (Integer.valueOf(0).equals(result.exitCode())) {
                return new ProjectCommandResult(
                        ProjectCommandResult.Status.SUCCESS,
                        result.command(),
                        result.exitCode(),
                        result.stdout(),
                        null
                );
            }
            return new ProjectCommandResult(
                    ProjectCommandResult.Status.FAILED,
                    result.command(),
                    result.exitCode(),
                    result.stdout(),
                    "命令退出码: " + result.exitCode()
            );
        }
        return new ProjectCommandResult(
                switch (result.status()) {
                    case TIMED_OUT -> ProjectCommandResult.Status.TIMED_OUT;
                    case IDLE_TIMED_OUT -> ProjectCommandResult.Status.IDLE_TIMED_OUT;
                    case INTERRUPTED -> ProjectCommandResult.Status.INTERRUPTED;
                    case START_FAILED -> ProjectCommandResult.Status.START_FAILED;
                    case COMPLETED -> throw new IllegalStateException("已完成进程必须包含退出码结果");
                },
                result.command(),
                result.exitCode(),
                result.stdout(),
                result.errorDetail()
        );
    }

    private Map<String, String> controlledPnpmEnvironment() {
        return Map.of(
                "NO_UPDATE_NOTIFIER", "1",
                "NPM_CONFIG_AUDIT", "false",
                "NPM_CONFIG_FUND", "false",
                "CI", "true"
        );
    }

    private static boolean isWindowsOperatingSystem() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("windows");
    }
}
