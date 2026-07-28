package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import org.springframework.stereotype.Component;

import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeSpecSupport.frontendOptions;
import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeValueSupport.*;

/**
 * 基础配方对象工厂。
 */
@Component
final class BasicRecipeFactory {

    /** 创建{@code Basic}{@code Recipe}。 */
    BasicRecipe create(String userMessage, CreateSpec spec) {
        String brand = firstNonBlank(spec.product() == null ? null : spec.product().brandName(), inferBrand(userMessage, "Nexa Studio"));
        CreateSpec.Landing landing = spec.content() == null ? null : spec.content().landing();
        String headline = firstNonBlank(landing == null ? null : landing.headline(), brand + " 的数字化体验");
        String description = firstNonBlank(landing == null ? null : landing.description(),
                "围绕核心用户、服务流程和数据反馈，快速搭建可预览、可继续编辑的应用骨架。");
        String domain = firstNonBlank(readableDomain(spec.product() == null ? null : spec.product().domain()), inferIndustry(userMessage));
        String primary = validHex(spec.frontend() == null || spec.frontend().theme() == null ? null : spec.frontend().theme().primary(), "#2563eb");
        String accent = validHex(spec.frontend() == null || spec.frontend().theme() == null ? null : spec.frontend().theme().accent(), "#f97316");
        String entityLabel = spec.entities() == null || spec.entities().isEmpty()
                ? "业务"
                : firstNonBlank(spec.entities().getFirst().label(), "业务");
        return new BasicRecipe(brand, headline, description, domain, primary, accent, entityLabel, frontendOptions(spec));
    }
}
