package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateTemplatePlannerTest {

    private final CreateTemplatePlanner planner = createPlanner();

    @Test
    void shouldPlanVueAdminCrudCreatePath() {
        CreateGenerationPlan plan = planner.plan(CodeGenTypeEnum.VUE_PROJECT, "做一个带登录的商品管理后台 CRUD");

        assertEquals("vue-web-admin", plan.baseTemplateId());
        assertTrue(plan.moduleIds().contains("auth-login-register"));
        assertTrue(plan.moduleIds().contains("crud-table-form"));
        assertTrue(plan.moduleIds().contains("product-management"));
        assertTrue(allSlotIds(plan).contains("bulk_actions"));
        assertTrue(allSlotIds(plan).contains("inventory_data"));
        assertTrue(plan.slotGroups().size() >= 3);
        assertNoDuplicateSlots(plan);
    }

    @Test
    void shouldPlanGoSqliteCrudCreatePath() {
        CreateGenerationPlan plan = planner.plan(CodeGenTypeEnum.BACKEND_PROJECT, "做一个商品 CRUD 后端");

        assertEquals("go-sqlite-backend-basic", plan.baseTemplateId());
        assertTrue(plan.moduleIds().contains("backend-crud-api"));
        assertTrue(plan.moduleIds().contains("sqlite-schema"));
        assertTrue(plan.moduleIds().contains("server-wiring"));
    }

    @Test
    void shouldPlanFullStackCrudCreatePath() {
        CreateGenerationPlan plan = planner.plan(CodeGenTypeEnum.FULL_STACK_PROJECT, "做一个商品管理全栈 CRUD");

        assertEquals("vue-web-admin+go-sqlite-backend-basic", plan.baseTemplateId());
        assertTrue(plan.moduleIds().contains("admin-dashboard"), plan.moduleIds().toString());
        assertTrue(plan.moduleIds().contains("crud-table-form")
                || plan.moduleIds().contains("frontend-crud-admin"), plan.moduleIds().toString());
        assertTrue(plan.moduleIds().contains("backend-crud-api"), plan.moduleIds().toString());
        assertTrue(allSlotIds(plan).contains("full_stack_crud_api"),
                "全栈计划必须显式冻结前后端 CRUD API 桥接能力: " + allSlotIds(plan));
        assertNoDuplicateSlots(plan);
    }

    @Test
    void fullStackFeatureRequestMustPlanMatchingBackendCapabilities() {
        CreateGenerationPlan plan = planner.plan(
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                "做一个支持搜索、分页、批量导入导出的商品管理全栈系统"
        );

        assertTrue(plan.moduleIds().contains("backend-search"), plan.moduleIds().toString());
        assertTrue(plan.moduleIds().contains("backend-pagination"), plan.moduleIds().toString());
        assertTrue(plan.moduleIds().contains("backend-export"), plan.moduleIds().toString());
        assertTrue(allSlotIds(plan).containsAll(List.of(
                "module_search",
                "module_pagination",
                "module_import_export"
        )), allSlotIds(plan).toString());
        assertNoDuplicateSlots(plan);
    }

    @Test
    void shouldPlanMobileCommerceAndBookingWithFineGrainedSlots() {
        CreateGenerationPlan plan = planner.plan(CodeGenTypeEnum.VUE_PROJECT, "做一个手机端会员预约商品商城");

        assertEquals("vue-web-mobile", plan.baseTemplateId());
        List<String> slots = allSlotIds(plan);
        assertTrue(slots.contains("mobile_campaigns"));
        assertTrue(slots.contains("coupon_data"));
        assertTrue(slots.contains("booking_data"));
        assertTrue(slots.contains("profile_page"));
        assertNoDuplicateSlots(plan);
    }

    @Test
    void shouldPlanLandingWithConversionSlots() {
        CreateGenerationPlan plan = planner.plan(CodeGenTypeEnum.VUE_PROJECT, "做一个产品介绍落地页，包含价格方案和客户案例");

        assertEquals("vue-web-landing", plan.baseTemplateId());
        List<String> slots = allSlotIds(plan);
        assertEquals(List.of("landing_core_data"), slots);
        assertEquals(1, plan.slotGroups().size());
        assertNoDuplicateSlots(plan);
    }

    @Test
    void shouldPlanBasicContentProjectWithListDetailSlots() {
        CreateGenerationPlan plan = planner.plan(CodeGenTypeEnum.VUE_PROJECT, "做一个博客内容知识库");

        assertEquals("vue-web-basic", plan.baseTemplateId());
        List<String> slots = allSlotIds(plan);
        assertTrue(slots.contains("content_data"));
        assertTrue(slots.contains("search_bar"));
        assertTrue(slots.contains("pro_table"));
        assertNoDuplicateSlots(plan);
    }

    @Test
    void shouldPlanAdminOrderManagement() {
        CreateGenerationPlan plan = planner.plan(CodeGenTypeEnum.VUE_PROJECT, "做一个订单管理系统");
        assertEquals("vue-web-admin", plan.baseTemplateId());
        List<String> slots = allSlotIds(plan);
        assertTrue(slots.contains("orders_page"), "orders_page missing: " + slots);
        assertTrue(slots.contains("table_columns"), "table_columns missing: " + slots);
        assertNoDuplicateSlots(plan);
    }

    @Test
    void shouldPlanMobileSearchAndFilter() {
        CreateGenerationPlan plan = planner.plan(CodeGenTypeEnum.VUE_PROJECT, "做一个带搜索筛选功能的手机端应用");
        assertEquals("vue-web-mobile", plan.baseTemplateId());
        List<String> slots = allSlotIds(plan);
        assertTrue(slots.contains("search_page"), "search_page missing: " + slots);
        assertNoDuplicateSlots(plan);
    }

    @Test
    void shouldPlanLandingWithTestimonialsAndFeatures() {
        CreateGenerationPlan plan = planner.plan(CodeGenTypeEnum.VUE_PROJECT, "做一个产品落地页，展示功能优势和客户口碑评价");
        assertEquals("vue-web-landing", plan.baseTemplateId());
        List<String> slots = allSlotIds(plan);
        assertEquals(List.of("landing_core_data"), slots);
        assertEquals(1, plan.slotGroups().size());
        assertNoDuplicateSlots(plan);
    }

    @Test
    void shouldPlanBackendWithSearchExtension() {
        CreateGenerationPlan plan = planner.plan(CodeGenTypeEnum.BACKEND_PROJECT, "做一个带搜索功能的商品后端");
        assertEquals("go-sqlite-backend-basic", plan.baseTemplateId());
        assertTrue(plan.moduleIds().contains("backend-search"), plan.moduleIds().toString());
        assertTrue(allSlotIds(plan).contains("module_search"));
    }

    @Test
    void shouldPlanBackendWithExportExtension() {
        CreateGenerationPlan plan = planner.plan(CodeGenTypeEnum.BACKEND_PROJECT, "做一个支持导入导出的后端");
        assertEquals("go-sqlite-backend-basic", plan.baseTemplateId());
        assertTrue(plan.moduleIds().contains("backend-export"), plan.moduleIds().toString());
        assertTrue(allSlotIds(plan).contains("module_import_export"));
    }

    @Test
    void shouldPlanBasicGalleryAndContact() {
        CreateGenerationPlan plan = planner.plan(CodeGenTypeEnum.VUE_PROJECT, "做一个图库和联系页面的网站");
        assertEquals("vue-web-basic", plan.baseTemplateId());
        List<String> slots = allSlotIds(plan);
        assertTrue(slots.contains("gallery_page"), "gallery_page missing: " + slots);
        assertTrue(slots.contains("contact_page"), "contact_page missing: " + slots);
        assertNoDuplicateSlots(plan);
    }
    private List<String> allSlotIds(CreateGenerationPlan plan) {
        return plan.slotGroups().stream()
                .flatMap(group -> group.slotIds().stream())
                .toList();
    }

    private void assertNoDuplicateSlots(CreateGenerationPlan plan) {
        List<String> slots = plan.slotGroups().stream()
                .flatMap(group -> group.slotIds().stream().map(slot -> group.templateId() + ":" + slot))
                .toList();
        assertEquals(slots.size(), new HashSet<>(slots).size());
    }

    private CreateTemplatePlanner createPlanner() {
        CreateGenerationPlanAssembler assembler = new CreateGenerationPlanAssembler();
        VueTemplateFeaturePlanner frontendPlanner = new VueTemplateFeaturePlanner();
        BackendTemplateFeaturePlanner backendPlanner = new BackendTemplateFeaturePlanner();
        return new CreateTemplatePlanner(List.of(
                new VueCreateTemplatePlanningAdapter(frontendPlanner, assembler),
                new BackendCreateTemplatePlanningAdapter(backendPlanner, assembler),
                new FullStackCreateTemplatePlanningAdapter(frontendPlanner, backendPlanner, assembler)
        ));
    }
}
