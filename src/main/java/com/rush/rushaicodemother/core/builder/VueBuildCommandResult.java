package com.rush.rushaicodemother.core.builder;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandResult;

/**
 * Vue 构建过程中单条命令的不可变执行结果。
 */
public record VueBuildCommandResult(
        String command,
        boolean success,
        Integer exitCode,
        boolean timeout,
        String output,
        String errorMessage
) {

    public VueBuildCommandResult {
        command = StrUtil.blankToDefault(command, "unknown command");
        output = StrUtil.nullToEmpty(output);
    }

    static VueBuildCommandResult success(String command, int exitCode, String output) {
        return new VueBuildCommandResult(command, true, exitCode, false, output, null);
    }

    static VueBuildCommandResult fromProjectCommand(ProjectCommandResult result) {
        return new VueBuildCommandResult(
                result.command(),
                result.success(),
                result.exitCode(),
                result.timedOut(),
                result.output(),
                result.errorDetail()
        );
    }

    static VueBuildCommandResult skipped(String command, String output) {
        return new VueBuildCommandResult(command, true, 0, false, output, null);
    }

    static VueBuildCommandResult failed(String command, int exitCode, String output) {
        return new VueBuildCommandResult(command, false, exitCode, false, output, null);
    }

    static VueBuildCommandResult exception(String command, String errorMessage) {
        return new VueBuildCommandResult(command, false, null, false, "", errorMessage);
    }
}
