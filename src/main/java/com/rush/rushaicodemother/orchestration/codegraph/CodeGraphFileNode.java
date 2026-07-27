package com.rush.rushaicodemother.orchestration.codegraph;

import java.util.List;

/**
 * 代码图文件节点的不可变数据载体。
 */
public record CodeGraphFileNode(
        String relativePath,
        String extension,
        List<CodeGraphImport> imports,
        List<CodeGraphSymbol> symbols,
        List<String> diagnostics
) {

    public CodeGraphFileNode {
        imports = imports == null ? List.of() : List.copyOf(imports);
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
