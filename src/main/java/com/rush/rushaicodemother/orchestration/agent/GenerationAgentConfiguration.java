package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipeLibrary;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkillLibrary;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.GenerationContextCompressionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Production assembly for capabilities shared by generation-agent nodes. */
@Configuration(proxyBeanMethods = false)
public class GenerationAgentConfiguration {

    @Bean
    GenerationAgentSupport generationAgentSupport(
            GenerationRecipeLibrary recipeLibrary,
            GenerationSkillLibrary skillLibrary,
            WorkspaceSemanticIndexService semanticIndexService,
            GenerationContextCompressionService contextCompressionService,
            GenerationWorkspaceService generationWorkspaceService
    ) {
        return new GenerationAgentSupport(
                recipeLibrary,
                skillLibrary,
                semanticIndexService,
                contextCompressionService,
                generationWorkspaceService
        );
    }
}
