package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.springframework.stereotype.Component;

@Component
final class BackendCreateRecipeRenderer extends AbstractSlotRecipeRenderer<BackendRecipe> {

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
        super("go-sqlite-backend-basic", "AI spec + 本地 backend recipe 已生成 CRUD 分层模块");
        this.recipeFactory = recipeFactory;
        this.domainTemplates = domainTemplates;
        this.repositoryTemplate = repositoryTemplate;
        this.serviceTemplate = serviceTemplate;
        this.httpTemplates = httpTemplates;
    }

    @Override
    protected BackendRecipe createRecipe(String userMessage, CreateSpec spec) {
        return recipeFactory.create(userMessage, spec);
    }

    @Override
    protected PatchOperation renderSlot(String slotId, BackendRecipe recipe) {
        String moduleRoot = "internal/modules/" + recipe.packageName();
        return switch (slotId) {
            case "domain_contract" -> PatchOperation.modify("internal/domain/model.go", domainTemplates.domainContract(recipe));
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
