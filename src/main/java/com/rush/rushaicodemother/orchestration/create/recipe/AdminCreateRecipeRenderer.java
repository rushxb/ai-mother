package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.springframework.stereotype.Component;

/**
 * 管理端创建配方渲染器。
 */
@Component
final class AdminCreateRecipeRenderer extends AbstractSlotRecipeRenderer<AdminRecipe> {

    private final AdminRecipeFactory recipeFactory;
    private final AdminDashboardTemplate dashboardTemplate;
    private final AdminDataTemplates dataTemplates;
    private final AdminThemeTemplate themeTemplate;

    AdminCreateRecipeRenderer(AdminRecipeFactory recipeFactory,
                              AdminDashboardTemplate dashboardTemplate,
                              AdminDataTemplates dataTemplates,
                              AdminThemeTemplate themeTemplate) {
        super("vue-web-admin", "AI spec + 本地 admin recipe 已生成后台数据与页面");
        this.recipeFactory = recipeFactory;
        this.dashboardTemplate = dashboardTemplate;
        this.dataTemplates = dataTemplates;
        this.themeTemplate = themeTemplate;
    }

    @Override
    protected AdminRecipe createRecipe(String userMessage, CreateSpec spec) {
        return recipeFactory.create(userMessage, spec);
    }

    /**
 * 渲染插槽。
 *
 * @param slotId 插槽编号
 * @param recipe {@code recipe} 对应的调用参数
 * @return 插槽
 */
    @Override
    protected PatchOperation renderSlot(String slotId, AdminRecipe recipe) {
        return switch (slotId) {
            case "dashboard_content" -> PatchOperation.modify("src/views/DashboardView.vue", dashboardTemplate.adminDashboardView(recipe));
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
}
