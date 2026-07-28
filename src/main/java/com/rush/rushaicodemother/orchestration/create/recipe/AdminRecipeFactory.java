package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeSpecSupport.frontendOptions;
import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeSpecSupport.normalizeFields;
import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeValueSupport.*;

/**
 * 管理端配方对象工厂。
 */
@Component
final class AdminRecipeFactory {

    /** 创建管理端{@code Recipe}。 */
    AdminRecipe create(String userMessage, CreateSpec spec) {
        String brand = firstNonBlank(spec.product() == null ? null : spec.product().brandName(), inferBrand(userMessage, "运营中台"));
        String domain = firstNonBlank(readableDomain(spec.product() == null ? null : spec.product().domain()), inferIndustry(userMessage));
        String primary = validHex(spec.frontend() == null || spec.frontend().theme() == null ? null : spec.frontend().theme().primary(), "#2563eb");
        String accent = validHex(spec.frontend() == null || spec.frontend().theme() == null ? null : spec.frontend().theme().accent(), "#f97316");
        List<CreateSpec.EntitySpec> entities = spec.entities() == null || spec.entities().isEmpty()
                ? List.of(new CreateSpec.EntitySpec("Record", "业务记录", List.of(
                new CreateSpec.FieldSpec("name", "string", "名称", true, List.of()),
                new CreateSpec.FieldSpec("owner", "string", "负责人", false, List.of()),
                new CreateSpec.FieldSpec("status", "enum", "状态", false, List.of("启用", "停用")),
                new CreateSpec.FieldSpec("amount", "decimal", "金额", false, List.of())
        ), List.of(), List.of()))
                : spec.entities().stream().limit(3).toList();
        CreateSpec.EntitySpec primaryEntity = entities.getFirst();
        String entityLabel = firstNonBlank(primaryEntity.label(), "业务记录");
        List<RecipeField> recipeFields = normalizeFields(primaryEntity.fields());
        FrontendOptions frontend = frontendOptions(spec);
        return new AdminRecipe(brand, domain, primary, accent, entityLabel, recipeFields, frontend,
                firstNonBlank(spec.content() == null ? null : spec.content().mockDataStyle(), domain + "运营数据"));
    }
}
