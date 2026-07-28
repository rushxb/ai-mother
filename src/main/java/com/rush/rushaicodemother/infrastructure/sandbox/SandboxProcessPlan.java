package com.rush.rushaicodemother.infrastructure.sandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 由沙箱后端生成的主机进程规范。 */
public record SandboxProcessPlan(
        String backend,
        Path hostWorkingDirectory,
        List<String> hostCommand,
        Map<String, String> hostEnvironment,
        Set<String> hostEnvironmentVariablesToRemove,
        String cleanupResourceId,
        List<List<String>> activationCommands,
        List<String> cleanupResourceIds
) {

    /**
 * 创建{@code Sandbox}进程计划实例并完成必要的依赖和初始状态设置。
 *
 * @param backend 后端
 * @param hostWorkingDirectory {@code hostWorkingDirectory} 对应的调用参数
 * @param hostCommand 主机命令
 * @param hostEnvironment {@code hostEnvironment} 对应的调用参数
 * @param hostEnvironmentVariablesToRemove {@code hostEnvironmentVariablesToRemove} 对应的调用参数
 * @param cleanupResourceId 目标资源编号
 */
    public SandboxProcessPlan(
            String backend,
            Path hostWorkingDirectory,
            List<String> hostCommand,
            Map<String, String> hostEnvironment,
            Set<String> hostEnvironmentVariablesToRemove,
            String cleanupResourceId
    ) {
        this(
                backend,
                hostWorkingDirectory,
                hostCommand,
                hostEnvironment,
                hostEnvironmentVariablesToRemove,
                cleanupResourceId,
                List.of(),
                cleanupResourceId == null || cleanupResourceId.isBlank()
                        ? List.of()
                        : List.of(cleanupResourceId)
        );
    }

    /** 创建{@code Sandbox}进程计划实例并完成必要的依赖和初始状态设置。 */
    public SandboxProcessPlan {
        backend = backend == null || backend.isBlank() ? "unknown" : backend.trim();
        hostCommand = hostCommand == null ? List.of() : List.copyOf(hostCommand);
        hostEnvironment = hostEnvironment == null ? Map.of() : Map.copyOf(hostEnvironment);
        hostEnvironmentVariablesToRemove = hostEnvironmentVariablesToRemove == null
                ? Set.of()
                : Set.copyOf(hostEnvironmentVariablesToRemove);
        cleanupResourceId = cleanupResourceId == null ? "" : cleanupResourceId.trim();
        activationCommands = activationCommands == null
                ? List.of()
                : activationCommands.stream().map(List::copyOf).toList();
        cleanupResourceIds = cleanupResourceIds == null
                ? List.of()
                : cleanupResourceIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
