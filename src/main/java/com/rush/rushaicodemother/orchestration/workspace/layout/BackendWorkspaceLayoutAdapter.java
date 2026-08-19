package com.rush.rushaicodemother.orchestration.workspace.layout;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Set;

/** 后端工程使用规范工作区本身作为后端根目录，不声明前端角色。 */
@Component
public class BackendWorkspaceLayoutAdapter implements GenerationWorkspaceLayoutAdapter {

    @Override
    public Set<CodeGenTypeEnum> supportedTypes() {
        return Set.of(CodeGenTypeEnum.BACKEND_PROJECT);
    }

    @Override
    public GenerationWorkspaceLayout resolve(Path canonicalRootPath) {
        return new GenerationWorkspaceLayout(null, canonicalRootPath);
    }
}
