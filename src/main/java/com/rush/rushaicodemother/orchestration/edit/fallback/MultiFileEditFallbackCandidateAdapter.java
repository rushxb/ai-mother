package com.rush.rushaicodemother.orchestration.edit.fallback;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** 原生静态多文件项目的 HTML、样式与脚本入口候选 adapter。 */
@Component
@Order(20)
public class MultiFileEditFallbackCandidateAdapter implements EditFallbackCandidateAdapter {

    private static final Set<CodeGenTypeEnum> SUPPORTED_TYPES = Set.of(CodeGenTypeEnum.MULTI_FILE);
    private static final List<String> ENTRY_PATHS = List.of(
            "index.html",
            "style.css",
            "script.js"
    );

    @Override
    public Set<CodeGenTypeEnum> supportedCodeGenTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public List<Path> candidatePaths(GenerationWorkspace workspace) {
        Path frontendRoot = workspace == null ? null : workspace.frontendRootPath();
        if (frontendRoot == null) {
            return List.of();
        }
        return ENTRY_PATHS.stream().map(frontendRoot::resolve).toList();
    }
}
