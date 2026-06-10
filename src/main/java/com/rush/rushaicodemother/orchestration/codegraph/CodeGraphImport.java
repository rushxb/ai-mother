package com.rush.rushaicodemother.orchestration.codegraph;

public record CodeGraphImport(
        String sourceFile,
        String importedPath,
        String resolvedFile,
        String kind
) {
}
