package com.rush.rushaicodemother.orchestration.codegraph;

/**
 * 代码图导入的不可变数据载体。
 */
public record CodeGraphImport(
        String sourceFile,
        String importedPath,
        String resolvedFile,
        String kind
) {
}
