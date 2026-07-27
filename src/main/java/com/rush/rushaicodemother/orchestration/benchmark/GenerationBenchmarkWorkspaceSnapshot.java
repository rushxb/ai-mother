package com.rush.rushaicodemother.orchestration.benchmark;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 在确定性夹具设置后捕获的内容寻址源工作区基线。 */
public record GenerationBenchmarkWorkspaceSnapshot(
        Path root,
        Map<String, String> fileDigests
) {
    public GenerationBenchmarkWorkspaceSnapshot {
        if (root == null) {
            throw new IllegalArgumentException("benchmark workspace root is required");
        }
        root = root.toAbsolutePath().normalize();
        fileDigests = fileDigests == null ? Map.of() : Map.copyOf(fileDigests);
    }

    public Set<String> changedPaths(GenerationBenchmarkWorkspaceSnapshot current) {
        if (current == null || !root.equals(current.root())) {
            throw new IllegalArgumentException("benchmark snapshots must use the same workspace root");
        }
        Set<String> paths = new LinkedHashSet<>(fileDigests.keySet());
        paths.addAll(current.fileDigests().keySet());
        paths.removeIf(path -> java.util.Objects.equals(
                fileDigests.get(path), current.fileDigests().get(path)));
        return Set.copyOf(paths);
    }
}
