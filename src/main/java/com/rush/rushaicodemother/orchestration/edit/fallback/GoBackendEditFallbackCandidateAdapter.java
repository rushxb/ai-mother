package com.rush.rushaicodemother.orchestration.edit.fallback;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Go 后端及 Full Stack 后端角色的入口候选 adapter。 */
@Component
@Order(30)
public class GoBackendEditFallbackCandidateAdapter implements EditFallbackCandidateAdapter {

    private static final Set<CodeGenTypeEnum> SUPPORTED_TYPES = Set.of(
            CodeGenTypeEnum.BACKEND_PROJECT,
            CodeGenTypeEnum.FULL_STACK_PROJECT
    );
    private static final List<String> ENTRY_PATHS = List.of(
            "cmd/server/main.go",
            "go.mod"
    );

    @Override
    public Set<CodeGenTypeEnum> supportedCodeGenTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public List<Path> candidatePaths(GenerationWorkspace workspace) {
        Path backendRoot = workspace == null ? null : workspace.backendRootPath();
        return resolvePaths(backendRoot, ENTRY_PATHS);
    }

    private List<Path> resolvePaths(Path root, List<String> relativePaths) {
        if (root == null) {
            return List.of();
        }
        return relativePaths.stream().map(root::resolve).toList();
    }
}
