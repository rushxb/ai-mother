package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeSpecSupport.*;
import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeValueSupport.*;

/**
 * Backend配方对象工厂。
 */
@Component
final class BackendRecipeFactory {

    private static final int MAX_RENDERED_ENTITIES = 4;

    /**
     * 为规格中的每个业务实体创建后端配方。
     *
     * <p>渲染层必须完整消费已经冻结的实体集合，禁止只取首个实体后仍上报完整覆盖。</p>
     */
    List<BackendRecipe> createAll(String userMessage, CreateSpec spec) {
        List<CreateSpec.EntitySpec> entities = resolvedEntities(userMessage, spec);
        if (entities.size() > MAX_RENDERED_ENTITIES) {
            throw new IllegalArgumentException("后端快速生成最多支持 " + MAX_RENDERED_ENTITIES + " 个实体");
        }
        Map<String, BackendRecipe> recipesByPackage = new LinkedHashMap<>();
        for (CreateSpec.EntitySpec entity : entities) {
            BackendRecipe recipe = createRecipe(userMessage, spec, entity);
            BackendRecipe duplicate = recipesByPackage.putIfAbsent(recipe.packageName(), recipe);
            if (duplicate != null) {
                throw new IllegalArgumentException("后端实体包名重复：" + recipe.packageName());
            }
        }
        return List.copyOf(recipesByPackage.values());
    }

    private List<CreateSpec.EntitySpec> resolvedEntities(String userMessage, CreateSpec spec) {
        if (spec.entities() != null && !spec.entities().isEmpty()) {
            return spec.entities();
        }
        return List.of(new CreateSpec.EntitySpec(
                inferEntityName(userMessage), inferEntityLabel(userMessage), List.of(
                new CreateSpec.FieldSpec("name", "string", "名称", true, List.of()),
                new CreateSpec.FieldSpec("status", "enum", "状态", false, List.of("启用", "停用")),
                new CreateSpec.FieldSpec("owner", "string", "负责人", false, List.of()),
                new CreateSpec.FieldSpec("remark", "string", "备注", false, List.of())
        ), List.of(), List.of()));
    }

    private BackendRecipe createRecipe(String userMessage,
                                       CreateSpec spec,
                                       CreateSpec.EntitySpec entity) {
        String structName = pascal(firstNonBlank(entity.name(), inferEntityName(userMessage)));
        String label = firstNonBlank(entity.label(), inferEntityLabel(userMessage));
        String packageName = lowerIdentifier(structName);
        List<RecipeField> fields = normalizeFields(entity.fields());
        BackendOptions options = backendOptions(spec);
        return new BackendRecipe(packageName, structName, label, tableName(packageName), fields, options,
                databaseIndexes(spec, fields));
    }
}
