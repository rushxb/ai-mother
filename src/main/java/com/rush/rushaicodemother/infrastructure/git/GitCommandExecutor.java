package com.rush.rushaicodemother.infrastructure.git;

import com.rush.rushaicodemother.config.GenerationCommitProperties;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessOutputLogPolicy;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessResult;
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

    public GitCommandExecutor(
            ManagedProcessExecutor processExecutor,
            GenerationCommitProperties properties
    ) {
        this.processExecutor = processExecutor;
        this.properties = properties;
    }

    public GitCommandResult execute(
            Path workingDirectory,
            List<String> arguments,
            Map<String, String> additionalEnvironment,
            String logContext
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
        // Security and reproducibility settings are applied last so callers cannot override them.
        environment.putAll(CONTROLLED_ENVIRONMENT);
        Path isolatedConfigRoot = workingDirectory.toAbsolutePath()
                .normalize()
                .resolve(".ai-code-mother-git-config");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", isolatedConfigRoot.resolve("global").toString());
        environment.put("XDG_CONFIG_HOME", isolatedConfigRoot.resolve("xdg").toString());
        ManagedProcessResult result = processExecutor.execute(
                ManagedProcessRequest.builder()
                        .workingDirectory(workingDirectory)
                        .command(command)
                        .displayCommand(displayCommand(arguments))
                        .environment(environment)
                        .timeout(properties.getCommandTimeout())
                        .heartbeatInterval(properties.getHeartbeatInterval())
                        .outputDrainTimeout(properties.getOutputDrainTimeout())
                        .maxOutputLength(properties.getMaxOutputLength())
                        .redirectErrorStream(false)
                        .outputLogPolicy(ManagedProcessOutputLogPolicy.SUMMARY)
                        .logCategory("git-command")
                        .logContext(logContext)
                        .build()
        );
        return new GitCommandResult(
                result.status(),
                result.exitCode(),
                result.stdout(),
                result.stderr(),
                result.errorDetail()
        );
    }

    public GitCommandResult execute(
            Path workingDirectory,
            List<String> arguments,
            String logContext
    ) {
        return execute(workingDirectory, arguments, Map.of(), logContext);
    }

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
