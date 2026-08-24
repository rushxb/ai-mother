package com.rush.rushaicodemother.orchestration.decision;

import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipe;
import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipeLibrary;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkill;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkillLibrary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 在场景冻结前一次性选择工程 recipe 与 skill。
 *
 * <p>这是唯一允许根据用户请求匹配工程指引的 module；下游 Agent 只消费选择快照。</p>
 */
@Component
public class GenerationGuidanceSelector {

    private final GenerationRecipeLibrary recipeLibrary;
    private final GenerationSkillLibrary skillLibrary;

    public GenerationGuidanceSelector(
            GenerationRecipeLibrary recipeLibrary,
            GenerationSkillLibrary skillLibrary
    ) {
        this.recipeLibrary = Objects.requireNonNull(recipeLibrary, "recipeLibrary 不能为空");
        this.skillLibrary = Objects.requireNonNull(skillLibrary, "skillLibrary 不能为空");
    }

    public GenerationGuidanceSelection select(String userMessage) {
        List<GenerationRecipe> recipes = recipeLibrary.match(userMessage, "");
        List<GenerationSkill> skills = skillLibrary.match(userMessage);
        return new GenerationGuidanceSelection(
                recipeLibrary.toPayloads(recipes),
                skillLibrary.toPayloads(skills)
        );
    }
}
