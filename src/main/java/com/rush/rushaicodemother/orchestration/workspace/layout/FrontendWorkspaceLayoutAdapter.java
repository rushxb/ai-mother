package com.rush.rushaicodemother.orchestration.workspace.layout;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Set;

/** HTML、多文件与 Vue 工程共享的单前端根目录布局。 */
@Component
public class FrontendWorkspaceLayoutAdapter implements GenerationWorkspaceLayoutAdapter {

    private static final Set<CodeGenTypeEnum> SUPPORTED_TYPES = Set.of(
            CodeGenTypeEnum.HTML,
            CodeGenTypeEnum.MULTI_FILE,
            CodeGenTypeEnum.VUE_PROJECT
    );

    @Override
    public Set<CodeGenTypeEnum> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public GenerationWorkspaceLayout resolve(Path canonicalRootPath) {
        return new GenerationWorkspaceLayout(canonicalRootPath, null);
    }
}
