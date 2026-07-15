package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemTestFactory;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipeLibrary;
import com.rush.rushaicodemother.orchestration.review.BackendQualityReviewService;
import com.rush.rushaicodemother.orchestration.review.VueSecurityReviewService;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkillLibrary;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.impl.GenerationContextCompressionServiceImpl;

import java.nio.file.Path;

/** Agent test assembly that mirrors the production dependency graph. */
public final class GenerationAgentTestFixture {

    private GenerationAgentTestFixture() {
    }

    public static GenerationAgentSupport support() {
        return support(new GenerationSkillLibrary(), new GenerationWorkspaceService(new CodeStorageProperties()));
    }

    public static GenerationAgentSupport support(Path codeOutputRoot) {
        return support(new GenerationSkillLibrary(), workspaceService(codeOutputRoot));
    }

    public static GenerationAgentSupport support(GenerationSkillLibrary skillLibrary, Path codeOutputRoot) {
        return support(skillLibrary, workspaceService(codeOutputRoot));
    }

    private static GenerationAgentSupport support(
            GenerationSkillLibrary skillLibrary,
            GenerationWorkspaceService generationWorkspaceService
    ) {
        return new GenerationAgentSupport(
                new GenerationRecipeLibrary(),
                skillLibrary,
                new WorkspaceSemanticIndexService(WorkspaceFileSystemTestFactory.create()),
                new GenerationContextCompressionServiceImpl(),
                generationWorkspaceService
        );
    }

    private static GenerationWorkspaceService workspaceService(Path codeOutputRoot) {
        Path outputRoot = codeOutputRoot.toAbsolutePath().normalize();
        CodeStorageProperties storageProperties = new CodeStorageProperties();
        storageProperties.setOutputRootDir(outputRoot);
        return new GenerationWorkspaceService(storageProperties);
    }

    public static CodeAgentNode codeAgentNode() {
        return new CodeAgentNode(new GenerationContextCompressionServiceImpl());
    }

    public static ReviewAgentNode reviewAgentNode() {
        return new ReviewAgentNode(new VueSecurityReviewService(), new BackendQualityReviewService());
    }
}
