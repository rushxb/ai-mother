package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeSpecSupport.*;
import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeValueSupport.*;

/**
 * Backend配方对象工厂。
 */
@Component
final class BackendRecipeFactory {

    /** 创建后端{@code Recipe}。 */
    BackendRecipe create(String userMessage, CreateSpec spec) {
        CreateSpec.EntitySpec entity = spec.entities() == null || spec.entities().isEmpty()
                ? new CreateSpec.EntitySpec(inferEntityName(userMessage), inferEntityLabel(userMessage), List.of(
                new CreateSpec.FieldSpec("name", "string", "名称", true, List.of()),
                new CreateSpec.FieldSpec("status", "enum", "状态", false, List.of("启用", "停用")),
                new CreateSpec.FieldSpec("owner", "string", "负责人", false, List.of()),
                new CreateSpec.FieldSpec("remark", "string", "备注", false, List.of())
        ), List.of(), List.of())
                : spec.entities().getFirst();
        String structName = pascal(firstNonBlank(entity.name(), inferEntityName(userMessage)));
        String label = firstNonBlank(entity.label(), inferEntityLabel(userMessage));
        String packageName = lowerIdentifier(structName);
        List<RecipeField> fields = normalizeFields(entity.fields());
        BackendOptions options = backendOptions(spec);
        return new BackendRecipe(packageName, structName, label, tableName(packageName), fields, options,
                databaseIndexes(spec, fields));
    }
}
