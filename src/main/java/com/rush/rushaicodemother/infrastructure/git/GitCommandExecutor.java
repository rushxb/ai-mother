package com.rush.rushaicodemother.infrastructure.git;

import com.rush.rushaicodemother.config.GenerationCommitProperties;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessOutputLogPolicy;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessResult;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 非交互式 Git 命令适配器。
 *
 * <p>统一禁用分页器、路径规则解释和终端凭据提示；调用方只能传入结构化参数。</p>
 */
@Component
public class GitCommandExecutor {

    private static final Map<String, String> CONTROLLED_ENVIRONMENT = Map.of(
            "GIT_AUTHOR_NAME", "ai-code-mother",
            "GIT_AUTHOR_EMAIL", "ai-code-mother@example.com",
            "GIT_COMMITTER_NAME", "ai-code-mother",
            "GIT_COMMITTER_EMAIL", "ai-code-mother@example.com",
            "GIT_TERMINAL_PROMPT", "0",
            "GCM_INTERACTIVE", "never"
    );

    private final ManagedProcessExecutor processExecutor;
    private final GenerationCommitProperties properties;
    private final GenerationExecutionContextService executionContextService;

    public GitCommandExecutor(
            ManagedProcessExecutor processExecutor,
            GenerationCommitProperties properties,
            GenerationExecutionContextService executionContextService
    ) {
        this.processExecutor = processExecutor;
        this.properties = properties;
        this.executionContextService = executionContextService;
    }

    /**
 * 执行{@code Git}命令处理流程。
 *
 * @param workingDirectory {@code workingDirectory} 对应的调用参数
 * @param arguments 参数
 * @param additionalEnvironment {@code additionalEnvironment} 对应的调用参数
 * @param logContext 日志上下文
 * @return {@code Git}命令
 */
    public GitCommandResult execute(
            Path workingDirectory,
            List<String> arguments,
            Map<String, String> additionalEnvironment,
            String logContext
    ) {
        return execute(workingDirectory, arguments, additionalEnvironment, logContext, null);
    }

    /** 在生成任务边界内执行 Git 命令。 */
    public GitCommandResult execute(
            Path workingDirectory,
            List<String> arguments,
            Map<String, String> additionalEnvironment,
            String logContext,
            String taskId
    ) {
        List<String> command = new ArrayList<>(List.of(
                "git",
                "--no-pager",
                "--literal-pathspecs",
                "-c",
                "core.quotepath=false",
                "-c",
                "core.fsmonitor=false"
        ));
        command.addAll(arguments);

        Map<String, String> environment = new LinkedHashMap<>();
        if (additionalEnvironment != null) {
            environment.putAll(additionalEnvironment);
        }
        // 安全性和可重复性设置最后应用，因此调用者无法覆盖它们。
        environment.putAll(CONTROLLED_ENVIRONMENT);
        Path isolatedConfigRoot = workingDirectory.toAbsolutePath()
                .normalize()
                .resolve(".ai-code-mother-git-config");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", isolatedConfigRoot.resolve("global").toString());
        environment.put("XDG_CONFIG_HOME", isolatedConfigRoot.resolve("xdg").toString());
        boolean taskScoped = taskId != null && !taskId.isBlank();
        ManagedProcessRequest.ManagedProcessRequestBuilder requestBuilder = ManagedProcessRequest.builder()
                        .workingDirectory(workingDirectory)
                        .command(command)
                        .displayCommand(displayCommand(arguments))
                        .environment(environment)
                        .timeout(taskScoped
                                ? executionContextService.clampTimeout(taskId, properties.getCommandTimeout())
                                : properties.getCommandTimeout())
                        .heartbeatInterval(properties.getHeartbeatInterval())
                        .outputDrainTimeout(properties.getOutputDrainTimeout())
                        .maxOutputLength(properties.getMaxOutputLength())
                        .redirectErrorStream(false)
                        .outputLogPolicy(ManagedProcessOutputLogPolicy.SUMMARY)
                        .logCategory("git-command")
                        .logContext(logContext);
        if (taskScoped) {
            requestBuilder.cancellationRequested(() -> executionContextService.shouldStop(taskId));
        }
        ManagedProcessResult result = processExecutor.execute(requestBuilder.build());
        if (taskScoped) {
            executionContextService.assertCanContinue(taskId);
        }
        return new GitCommandResult(
                result.status(),
                result.exitCode(),
                result.stdout(),
                result.stderr(),
                result.errorDetail()
        );
    }

    /**
 * 执行{@code Git}命令处理流程。
 *
 * @param workingDirectory {@code workingDirectory} 对应的调用参数
 * @param arguments 参数
 * @param logContext 日志上下文
 * @return {@code Git}命令
 */
    public GitCommandResult execute(
            Path workingDirectory,
            List<String> arguments,
            String logContext
    ) {
        return execute(workingDirectory, arguments, Map.of(), logContext);
    }

    /** 返回{@code display}命令。 */
    private String displayCommand(List<String> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "git";
        }
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (argument == null || argument.isBlank()) {
                return "git";
            }
            if (isGlobalOptionWithSeparateValue(argument)) {
                index++;
                continue;
            }
            if (argument.startsWith("-")) {
                continue;
            }
            return argument.matches("[A-Za-z0-9-]+") ? "git " + argument : "git";
        }
        return "git";
    }

    private boolean isGlobalOptionWithSeparateValue(String argument) {
        return "-c".equals(argument)
                || "-C".equals(argument)
                || "--config-env".equals(argument)
                || "--exec-path".equals(argument)
                || "--git-dir".equals(argument)
                || "--work-tree".equals(argument)
                || "--namespace".equals(argument)
                || "--super-prefix".equals(argument);
    }
}
