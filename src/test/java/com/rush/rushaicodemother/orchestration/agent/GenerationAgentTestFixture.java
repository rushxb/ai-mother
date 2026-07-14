package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemTestFactory;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipeLibrary;
import com.rush.rushaicodemother.orchestration.review.BackendQualityReviewService;
import com.rush.rushaicodemother.orchestration.review.VueSecurityReviewService;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkillLibrary;
import com.rush.rushaicodemother.service.impl.GenerationContextCompressionServiceImpl;

import java.nio.file.Path;

import static com.rush.rushaicodemother.constant.AppConstant.CODE_OUTPUT_ROOT_DIR;

/**
 * Agent 模块测试装配入口，确保测试使用与生产一致的完整依赖图。
 */
public final class GenerationAgentTestFixture {

    private GenerationAgentTestFixture() {
    }

    public static GenerationAgentSupport support() {
        return support(new GenerationSkillLibrary(), Path.of(CODE_OUTPUT_ROOT_DIR));
    }

    public static GenerationAgentSupport support(Path codeOutputRoot) {
        return support(new GenerationSkillLibrary(), codeOutputRoot);
    }

    public static GenerationAgentSupport support(GenerationSkillLibrary skillLibrary, Path codeOutputRoot) {
        return new GenerationAgentSupport(
                new GenerationRecipeLibrary(),
                skillLibrary,
                new WorkspaceSemanticIndexService(WorkspaceFileSystemTestFactory.create()),
                new GenerationContextCompressionServiceImpl(),
                codeOutputRoot
        );
    }

    public static CodeAgentNode codeAgentNode() {
        return new CodeAgentNode(new GenerationContextCompressionServiceImpl());
    }

    public static ReviewAgentNode reviewAgentNode() {
        return new ReviewAgentNode(new VueSecurityReviewService(), new BackendQualityReviewService());
    }
}
