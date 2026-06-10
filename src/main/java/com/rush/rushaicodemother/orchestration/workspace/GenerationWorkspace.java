package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.nio.file.Path;
import java.util.Set;

public record GenerationWorkspace(
        Long appId,
        CodeGenTypeEnum codeGenType,
        Path rootPath,
        Path canonicalRootPath,
        boolean exists,
        Path frontendRootPath,
        Path backendRootPath,
        Set<String> hiddenFileNames,
        Set<String> editableExtensions
) {
}
