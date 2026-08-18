package com.rush.rushaicodemother.orchestration.edit.fallback;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Vue 前端及 Full Stack 前端角色的入口候选 adapter。 */
@Component
@Order(20)
public class VueEditFallbackCandidateAdapter implements EditFallbackCandidateAdapter {

    private static final Set<CodeGenTypeEnum> SUPPORTED_TYPES = Set.of(
            CodeGenTypeEnum.VUE_PROJECT,
            CodeGenTypeEnum.FULL_STACK_PROJECT
    );
    private static final List<String> ENTRY_PATHS = List.of(
            "src/App.vue",
            "src/main.ts",
            "src/main.js"
    );

    @Override
    public Set<CodeGenTypeEnum> supportedCodeGenTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public List<Path> candidatePaths(GenerationWorkspace workspace) {
        Path frontendRoot = workspace == null ? null : workspace.frontendRootPath();
        return resolvePaths(frontendRoot, ENTRY_PATHS);
    }

    private List<Path> resolvePaths(Path root, List<String> relativePaths) {
        if (root == null) {
            return List.of();
        }
        return relativePaths.stream().map(root::resolve).toList();
    }
}
