package com.rush.rushaicodemother.orchestration.benchmark;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 在确定性夹具设置后捕获的内容寻址源工作区基线。 */
public record GenerationBenchmarkWorkspaceSnapshot(
        Path root,
        Map<String, String> fileDigests,
        GenerationBenchmarkWorkspaceIdentity identity
) {
    /** 保留仅比较同一物理根目录的无身份快照构造入口。 */
    public GenerationBenchmarkWorkspaceSnapshot(Path root, Map<String, String> fileDigests) {
        this(root, fileDigests, null);
    }

    public GenerationBenchmarkWorkspaceSnapshot {
        if (root == null) {
            throw new IllegalArgumentException("benchmark workspace root is required");
        }
        root = root.toAbsolutePath().normalize();
        fileDigests = fileDigests == null ? Map.of() : Map.copyOf(fileDigests);
    }

    /**
 * 返回变更{@code Paths}。
 *
 * @param current 当前
 * @return 生成基准测试工作区快照集合
 */
    public Set<String> changedPaths(GenerationBenchmarkWorkspaceSnapshot current) {
        if (current == null) {
            throw new IllegalArgumentException("Benchmark 当前工作区快照不能为空");
        }
        if (identity == null && current.identity() == null) {
            if (!root.equals(current.root())) {
                throw new IllegalArgumentException("无身份 Benchmark 快照必须使用同一工作区根目录");
            }
        } else if (identity == null || current.identity() == null
                || !identity.accepts(current.identity())) {
            throw new IllegalArgumentException("Benchmark 工作区快照逻辑身份不匹配");
        }
        Set<String> paths = new LinkedHashSet<>(fileDigests.keySet());
        paths.addAll(current.fileDigests().keySet());
        paths.removeIf(path -> java.util.Objects.equals(
                fileDigests.get(path), current.fileDigests().get(path)));
        return Set.copyOf(paths);
    }
}
