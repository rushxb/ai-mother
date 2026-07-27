package com.rush.rushaicodemother.orchestration.codegraph;

import java.util.List;
import java.util.Map;

/**
 * 工作区代码图的不可变数据载体。
 */
public record WorkspaceCodeGraph(
        String rootPath,
        List<CodeGraphFileNode> files,
        Map<String, List<String>> importsByFile,
        Map<String, List<String>> referencedByFile,
        Map<String, List<CodeGraphSymbol>> symbolsByName,
        List<String> diagnostics
) {

    public WorkspaceCodeGraph {
        files = files == null ? List.of() : List.copyOf(files);
        importsByFile = importsByFile == null ? Map.of() : Map.copyOf(importsByFile);
        referencedByFile = referencedByFile == null ? Map.of() : Map.copyOf(referencedByFile);
        symbolsByName = symbolsByName == null ? Map.of() : Map.copyOf(symbolsByName);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public List<String> referencedBy(String relativePath) {
        if (relativePath == null) {
            return List.of();
        }
        return referencedByFile.getOrDefault(relativePath.replace('\\', '/'), List.of());
    }
}
