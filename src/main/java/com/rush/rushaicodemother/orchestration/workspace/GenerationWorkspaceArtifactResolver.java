package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.artifact.CurrentGeneratedArtifactResolver;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Objects;

/** 使用原子发布指针解析当前生成制品，并兼容尚未迁移的遗留工作区。 */
@Component
public class GenerationWorkspaceArtifactResolver implements CurrentGeneratedArtifactResolver {

    private final GenerationWorkspaceService generationWorkspaceService;

    public GenerationWorkspaceArtifactResolver(GenerationWorkspaceService generationWorkspaceService) {
        this.generationWorkspaceService = Objects.requireNonNull(
                generationWorkspaceService,
                "generationWorkspaceService must not be null"
        );
    }

    @Override
    public Path resolve(Long appId, CodeGenTypeEnum codeGenType) {
        return generationWorkspaceService.resolveCanonical(appId, codeGenType).canonicalRootPath();
    }
}
