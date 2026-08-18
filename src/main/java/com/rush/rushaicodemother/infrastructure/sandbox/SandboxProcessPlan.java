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

    /** 兼容单资源计划，清理时同时将该资源纳入有序资源列表。 */
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

    /** 固化不可变快照，避免调用方在执行或清理期间篡改计划。 */
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
