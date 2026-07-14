package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipeLibrary;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkillLibrary;
import com.rush.rushaicodemother.service.GenerationContextCompressionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

import static com.rush.rushaicodemother.constant.AppConstant.CODE_OUTPUT_ROOT_DIR;

/**
 * 多智能体共享能力的生产装配入口。
 *
 * <p>工作区根目录仍遵循项目统一的代码输出目录约定；显式装配避免业务类内部创建
 * Spring 依赖，也保证生产和测试都经过同一个完整构造器。</p>
 */
@Configuration(proxyBeanMethods = false)
public class GenerationAgentConfiguration {

    @Bean
    GenerationAgentSupport generationAgentSupport(GenerationRecipeLibrary recipeLibrary,
                                                  GenerationSkillLibrary skillLibrary,
                                                  WorkspaceSemanticIndexService semanticIndexService,
                                                  GenerationContextCompressionService contextCompressionService) {
        return new GenerationAgentSupport(
                recipeLibrary,
                skillLibrary,
                semanticIndexService,
                contextCompressionService,
                Path.of(CODE_OUTPUT_ROOT_DIR)
        );
    }
}
