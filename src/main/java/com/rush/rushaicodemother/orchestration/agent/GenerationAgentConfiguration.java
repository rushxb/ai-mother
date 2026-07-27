package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.orchestration.context.GeneratedProjectContextService;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipeLibrary;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkillLibrary;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.GenerationContextCompressionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 生成代理节点共享功能的生产组装。 */
@Configuration(proxyBeanMethods = false)
public class GenerationAgentConfiguration {

    @Bean
    GenerationAgentSupport generationAgentSupport(
            GenerationRecipeLibrary recipeLibrary,
            GenerationSkillLibrary skillLibrary,
            WorkspaceSemanticIndexService semanticIndexService,
            GenerationContextCompressionService contextCompressionService,
            GenerationWorkspaceService generationWorkspaceService,
            GeneratedProjectContextService generatedProjectContextService
    ) {
        return new GenerationAgentSupport(
                recipeLibrary,
                skillLibrary,
                semanticIndexService,
                contextCompressionService,
                generationWorkspaceService,
                generatedProjectContextService
        );
    }
}
