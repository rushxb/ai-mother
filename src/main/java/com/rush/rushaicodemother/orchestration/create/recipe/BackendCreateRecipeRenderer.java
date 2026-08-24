package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.create.SlotGroup;
import com.rush.rushaicodemother.orchestration.create.TemplateVariableManifest;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

@Component
final class BackendCreateRecipeRenderer implements CreateRecipeRenderer {

    private static final String TEMPLATE_ID = "go-sqlite-backend-basic";
    private static final String SUMMARY = "AI spec + 本地 backend recipe 已生成 CRUD 分层模块";

    private final BackendRecipeFactory recipeFactory;
    private final BackendDomainTemplates domainTemplates;
    private final BackendRepositoryTemplate repositoryTemplate;
    private final BackendServiceTemplate serviceTemplate;
    private final BackendHttpTemplates httpTemplates;

    BackendCreateRecipeRenderer(BackendRecipeFactory recipeFactory,
                                BackendDomainTemplates domainTemplates,
                                BackendRepositoryTemplate repositoryTemplate,
                                BackendServiceTemplate serviceTemplate,
                                BackendHttpTemplates httpTemplates) {
        this.recipeFactory = recipeFactory;
        this.domainTemplates = domainTemplates;
        this.repositoryTemplate = repositoryTemplate;
        this.serviceTemplate = serviceTemplate;
        this.httpTemplates = httpTemplates;
    }

    @Override
    public String templateId() {
        return TEMPLATE_ID;
    }

    /**
     * 一个后端 slot 可能需要为多个实体展开多个补丁，因此不能使用“一槽一补丁”的通用基类。
     */
    @Override
    public RecipeRenderResult render(String userMessage,
                                     SlotGroup group,
                                     CreateSpec spec,
                                     TemplateVariableManifest manifest) {
        if (group == null || spec == null || !TEMPLATE_ID.equals(group.templateId())) {
            return RecipeRenderResult.empty();
        }
        List<BackendRecipe> recipes = recipeFactory.createAll(userMessage, spec);
        List<String> requestedSlots = group.slotIds() == null ? List.of() : group.slotIds();
        List<String> filledSlots = new ArrayList<>();
        List<PatchOperation> operations = new ArrayList<>();
        for (String slotId : new LinkedHashSet<>(requestedSlots)) {
            if (slotId == null || slotId.isBlank()) {
                continue;
            }
            List<PatchOperation> slotOperations = renderSlot(slotId, recipes);
            if (!slotOperations.isEmpty()) {
                operations.addAll(slotOperations);
                filledSlots.add(slotId);
            }
        }
        return RecipeRenderResult.of(
                requestedSlots,
                filledSlots,
                operations,
                SUMMARY + "（" + recipes.size() + " 个实体）",
                manifest
        );
    }

    private List<PatchOperation> renderSlot(String slotId, List<BackendRecipe> recipes) {
        if ("domain_contract".equals(slotId)) {
            return List.of(PatchOperation.modify(
                    "internal/domain/model.go",
                    domainTemplates.domainContract(recipes.getFirst())
            ));
        }
        List<PatchOperation> operations = recipes.stream()
                .map(recipe -> renderEntitySlot(slotId, recipe))
                .filter(Objects::nonNull)
                .toList();
        return operations.size() == recipes.size() ? operations : List.of();
    }

    private PatchOperation renderEntitySlot(String slotId, BackendRecipe recipe) {
        String moduleRoot = "internal/modules/" + recipe.packageName();
        return switch (slotId) {
            case "module_model" -> PatchOperation.add(moduleRoot + "/model.go", domainTemplates.backendModel(recipe));
            case "module_repository" -> PatchOperation.add(moduleRoot + "/repository.go", repositoryTemplate.backendRepository(recipe));
            case "module_service" -> PatchOperation.add(moduleRoot + "/service.go", serviceTemplate.backendService(recipe));
            case "module_handler" -> PatchOperation.add(moduleRoot + "/handler.go", httpTemplates.backendHandler(recipe));
            case "database_schema" -> PatchOperation.appendSqlMigration("sql/schema.sql", domainTemplates.backendSchema(recipe));
            case "module_import" -> PatchOperation.goAddImport("cmd/server/main.go", "backend-template/" + moduleRoot);
            case "server_wiring" -> PatchOperation.insertBeforeMarker(
                    "cmd/server/main.go", "// @AI_INJECT_MODULE_WIRING: register", httpTemplates.backendWiring(recipe));
            default -> null;
        };
    }
}
