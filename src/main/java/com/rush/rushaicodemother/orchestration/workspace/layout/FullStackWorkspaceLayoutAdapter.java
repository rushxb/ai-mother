package com.rush.rushaicodemother.orchestration.workspace.layout;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Set;

/** 全栈工程在规范根目录下隔离前端与后端两个角色目录。 */
@Component
public class FullStackWorkspaceLayoutAdapter implements GenerationWorkspaceLayoutAdapter {

    @Override
    public Set<CodeGenTypeEnum> supportedTypes() {
        return Set.of(CodeGenTypeEnum.FULL_STACK_PROJECT);
    }

    @Override
    public GenerationWorkspaceLayout resolve(Path canonicalRootPath) {
        return new GenerationWorkspaceLayout(
                canonicalRootPath.resolve("frontend"),
                canonicalRootPath.resolve("backend")
        );
    }
}
