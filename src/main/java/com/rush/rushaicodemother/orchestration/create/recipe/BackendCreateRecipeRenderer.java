package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.create.BackendRecipeCapability;
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
            SlotRenderOutcome slotOutcome = renderSlot(slotId, recipes, spec);
            if (slotOutcome.supported()) {
                operations.addAll(slotOutcome.operations());
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

    private SlotRenderOutcome renderSlot(String slotId,
                                         List<BackendRecipe> recipes,
                                         CreateSpec spec) {
        if ("domain_contract".equals(slotId)) {
            return SlotRenderOutcome.supported(List.of(PatchOperation.modify(
                    "internal/domain/model.go",
                    domainTemplates.domainContract(recipes.getFirst())
            )));
        }
        var capability = BackendRecipeCapability.fromSlotId(slotId);
        if (capability.isPresent()) {
            return capability.get().isEnabled(spec.backend())
                    ? SlotRenderOutcome.supported(List.of())
                    : SlotRenderOutcome.unsupported();
        }
        List<PatchOperation> operations = recipes.stream()
                .map(recipe -> renderEntitySlot(slotId, recipe))
                .filter(Objects::nonNull)
                .toList();
        return operations.size() == recipes.size()
                ? SlotRenderOutcome.supported(operations)
                : SlotRenderOutcome.unsupported();
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

    /** 一个 slot 可以由已有补丁中的行为满足，因此“支持”和“新增补丁”必须分别表达。 */
    private record SlotRenderOutcome(boolean supported, List<PatchOperation> operations) {

        private SlotRenderOutcome {
            operations = List.copyOf(operations == null ? List.of() : operations);
        }

        private static SlotRenderOutcome supported(List<PatchOperation> operations) {
            return new SlotRenderOutcome(true, operations);
        }

        private static SlotRenderOutcome unsupported() {
            return new SlotRenderOutcome(false, List.of());
        }
    }
}
