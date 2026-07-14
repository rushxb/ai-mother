package com.rush.rushaicodemother.infrastructure.process;

import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 项目脚本命令适配器，负责 pnpm 命令构造和项目命令结果语义。
 */
@Slf4j
@Component
public class ProjectCommandExecutor {

    private final ProjectCommandProperties properties;
    private final ManagedProcessExecutor processExecutor;
    private final GenerationExecutionContextService executionContextService;
    private final NodeToolchain nodeToolchain;

    @Autowired
    public ProjectCommandExecutor(
            ProjectCommandProperties properties,
            ManagedProcessExecutor processExecutor,
            GenerationExecutionContextService executionContextService,
            NodeToolchain nodeToolchain
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.processExecutor = Objects.requireNonNull(processExecutor, "processExecutor must not be null");
        this.executionContextService = Objects.requireNonNull(
                executionContextService,
                "executionContextService must not be null"
        );
        this.nodeToolchain = Objects.requireNonNull(nodeToolchain, "nodeToolchain must not be null");
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
            throw new IllegalArgumentException("脚本名称不能为空");
        }
        Duration effectiveTimeout = executionContextService.clampTimeout(taskId, commandTimeout);
        ProjectCommandResult result = execute(
                projectDirectory,
                List.of(nodeToolchain.pnpmExecutable(), "run", scriptName),
                effectiveTimeout,
                taskId,
                logContext
        );
        executionContextService.assertCanContinue(taskId);
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
                        .environment(NodeProcessEnvironment.overrides(true))
                        .environmentVariablesToRemove(NodeProcessEnvironment.variablesToRemove())
                        .timeout(commandTimeout)
                        .idleTimeout(properties.getIdleTimeout())
                        .heartbeatInterval(properties.getHeartbeatInterval())
                        .outputDrainTimeout(properties.getOutputDrainTimeout())
                        .maxOutputLength(properties.getMaxOutputLength())
                        .redirectErrorStream(true)
                        .logCategory("project-command")
                        .logContext(logContext)
                        .cancellationRequested(() -> executionContextService.shouldStop(taskId))
                        .build()
        );
        ProjectCommandResult result = toProjectCommandResult(managedResult);
        if (result.success()) {
            log.info("项目命令执行成功: command={}", result.command());
        } else {
            log.warn("项目命令执行失败: command={}, status={}, exitCode={}",
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

}
