package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.create.AdminRecipeCapability;
import com.rush.rushaicodemother.orchestration.create.SlotGroup;
import com.rush.rushaicodemother.orchestration.create.TemplateVariableManifest;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 管理端创建配方渲染器。
 *
 * <p>管理 CRUD 的搜索、筛选、表格、表单和批量操作共享同一个工作台状态模型，
 * 因此一个页面补丁可以同时证明多个能力 slot。这里显式区分“能力已支持”和
 * “本 slot 新增了补丁”，避免为同一文件制造互相覆盖的 MODIFY 操作。</p>
 */
@Component
final class AdminCreateRecipeRenderer implements CreateRecipeRenderer {

    private static final String TEMPLATE_ID = "vue-web-admin";
    private static final String SUMMARY = "AI spec + 本地 admin recipe 已生成可交互管理工作台";

    private final AdminRecipeFactory recipeFactory;
    private final AdminDashboardTemplate dashboardTemplate;
    private final AdminDataTemplates dataTemplates;
    private final AdminThemeTemplate themeTemplate;

    AdminCreateRecipeRenderer(AdminRecipeFactory recipeFactory,
                              AdminDashboardTemplate dashboardTemplate,
                              AdminDataTemplates dataTemplates,
                              AdminThemeTemplate themeTemplate) {
        this.recipeFactory = recipeFactory;
        this.dashboardTemplate = dashboardTemplate;
        this.dataTemplates = dataTemplates;
        this.themeTemplate = themeTemplate;
    }

    @Override
    public String templateId() {
        return TEMPLATE_ID;
    }

    @Override
    public RecipeRenderResult render(String userMessage,
                                     SlotGroup group,
                                     CreateSpec spec,
                                     TemplateVariableManifest manifest) {
        if (group == null || spec == null || !TEMPLATE_ID.equals(group.templateId())) {
            return RecipeRenderResult.empty();
        }

        AdminRecipe recipe = recipeFactory.create(userMessage, spec);
        List<String> requestedSlots = group.slotIds() == null ? List.of() : group.slotIds();
        Set<String> requestedSlotSet = new LinkedHashSet<>(requestedSlots);
        List<String> filledSlots = new ArrayList<>();
        List<PatchOperation> operations = new ArrayList<>();

        for (String slotId : requestedSlotSet) {
            if (slotId == null || slotId.isBlank()) {
                continue;
            }
            SlotRenderOutcome outcome = renderSlot(slotId, recipe, requestedSlotSet);
            if (outcome.supported()) {
                filledSlots.add(slotId);
                operations.addAll(outcome.operations());
            }
        }

        return RecipeRenderResult.of(
                requestedSlots,
                filledSlots,
                operations,
                SUMMARY,
                manifest
        );
    }

    private SlotRenderOutcome renderSlot(String slotId,
                                         AdminRecipe recipe,
                                         Set<String> requestedSlots) {
        PatchOperation fileOperation = renderFileSlot(slotId, recipe);
        if (fileOperation != null) {
            return SlotRenderOutcome.supported(fileOperation);
        }

        return AdminRecipeCapability.fromSlotId(slotId)
                .filter(capability -> capability.isProvidedBy(requestedSlots))
                .map(ignored -> SlotRenderOutcome.supportedWithoutPatch())
                .orElseGet(SlotRenderOutcome::unsupported);
    }

    private PatchOperation renderFileSlot(String slotId, AdminRecipe recipe) {
        return switch (slotId) {
            case "dashboard_content" -> PatchOperation.modify(
                    "src/views/DashboardView.vue",
                    dashboardTemplate.adminDashboardView(recipe)
            );
            case "mock_data" -> PatchOperation.modify("src/data/adminData.ts", dataTemplates.adminData(recipe));
            case "table_columns" -> PatchOperation.modify("src/data/table.columns.ts", dataTemplates.tableColumns(recipe));
            case "sidebar_menu" -> PatchOperation.modify("src/data/sidebar.menu.ts", dataTemplates.sidebarMenu(recipe));
            case "statistics_cards" -> PatchOperation.modify("src/data/statistics.ts", dataTemplates.statistics(recipe));
            case "operations_data" -> PatchOperation.modify("src/data/operations.ts", dataTemplates.operationsData(recipe));
            case "activity_timeline" -> PatchOperation.modify("src/data/activity.ts", dataTemplates.activityData(recipe));
            case "theme_tokens" -> PatchOperation.modify("src/styles/theme.css", themeTemplate.themeCss(recipe));
            default -> null;
        };
    }

    /** 一个能力可以由其他文件补丁共同提供，不要求额外制造同路径补丁。 */
    private record SlotRenderOutcome(boolean supported, List<PatchOperation> operations) {

        private SlotRenderOutcome {
            operations = List.copyOf(operations == null ? List.of() : operations);
        }

        private static SlotRenderOutcome supported(PatchOperation operation) {
            return new SlotRenderOutcome(true, List.of(operation));
        }

        private static SlotRenderOutcome supportedWithoutPatch() {
            return new SlotRenderOutcome(true, List.of());
        }

        private static SlotRenderOutcome unsupported() {
            return new SlotRenderOutcome(false, List.of());
        }
    }
}
