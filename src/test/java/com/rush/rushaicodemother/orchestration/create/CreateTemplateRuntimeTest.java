package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackGenerationContext;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackPortAllocator;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.template.SlotFillResult;
import com.rush.rushaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateTemplateRuntimeTest {

    @Test
    void shouldUseAiCreateSpecRecipeForLandingCreate() {
        Path workspaceRoot = Path.of("target/test-workspaces/create-template-runtime")
                .toAbsolutePath()
                .normalize();
        CreateSpecService createSpecService = mock(CreateSpecService.class);
        CreateRecipeRendererService recipeRendererService = new CreateRecipeRendererService(new LandingSlotFallbackRenderer());
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        Path projectRoot = workspaceRoot.resolve("vue_project_1").toAbsolutePath().normalize();
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), anyString()))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-landing",
                        projectRoot.toString(),
                        1
                ));
        when(vueBootstrapService.resolveProjectRoot(anyLong())).thenReturn(projectRoot);
        when(createSpecService.generate(anyString(), any()))
                .thenReturn(new CreateSpecService.SpecResult(true, fitnessSpec(), ""));
        when(patchApplyService.applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(PatchApplyResult.applied(1L, "task", projectRoot.toString(), 1, List.of("src/data/landingData.ts")));

        CreateTemplateRuntime runtime = new CreateTemplateRuntime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                new CreatePreWriteValidationService(new com.rush.rushaicodemother.orchestration.codegraph.StructuredSyntaxValidationService()),
                createSpecService,
                recipeRendererService,
                null,
                patchApplyService,
                new LandingSlotFallbackRenderer(),
                vueBootstrapService
        );

        SlotFillResult result = runtime.generate(app(), request("做一个 FitPilot 健身房 SaaS 官网"), landingDataPlan());

        assertEquals(1, result.filledSlotCount());
        assertEquals(1, result.patchOperationCount());
        String content = result.patchOperations().getFirst().content();
        assertTrue(content.contains("FitPilot"));
        assertTrue(content.contains("私教排课"));
        assertTrue(content.contains("#2563eb"));
        Map<?, ?> telemetry = (Map<?, ?>) result.metadata().get("telemetry");
        assertEquals(1, telemetry.get("aiCallCount"));
        assertEquals(false, telemetry.get("degraded"));
    }

    @Test
    void shouldUseAiCreateSpecRecipeForAdminCreate() {
        Path workspaceRoot = Path.of("target/test-workspaces/create-template-runtime")
                .toAbsolutePath()
                .normalize();
        CreateSpecService createSpecService = mock(CreateSpecService.class);
        CreateRecipeRendererService recipeRendererService = new CreateRecipeRendererService(new LandingSlotFallbackRenderer());
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        Path projectRoot = workspaceRoot.resolve("vue_project_1").toAbsolutePath().normalize();
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), anyString()))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-admin",
                        projectRoot.toString(),
                        1
                ));
        when(vueBootstrapService.resolveProjectRoot(anyLong())).thenReturn(projectRoot);
        when(createSpecService.generate(anyString(), any()))
                .thenReturn(new CreateSpecService.SpecResult(true, fitnessSpec(), ""));
        when(patchApplyService.applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(PatchApplyResult.applied(1L, "task", projectRoot.toString(), 4, List.of("src/data/adminData.ts")));

        CreateTemplateRuntime runtime = new CreateTemplateRuntime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                new CreatePreWriteValidationService(new com.rush.rushaicodemother.orchestration.codegraph.StructuredSyntaxValidationService()),
                createSpecService,
                recipeRendererService,
                null,
                patchApplyService,
                new LandingSlotFallbackRenderer(),
                vueBootstrapService
        );

        SlotFillResult result = runtime.generate(app(), request("做一个健身房课程管理后台"), adminPlan());

        assertTrue(result.patchOperationCount() >= 3);
        assertTrue(result.patchOperations().stream().anyMatch(operation -> operation.relativePath().equals("src/data/adminData.ts")
                && operation.content().contains("FitPilot")));
    }

    @Test
    void shouldKeepTemplateSkeletonWhenAnySlotGroupFails() {
        Path workspaceRoot = Path.of("target/test-workspaces/create-template-runtime")
                .toAbsolutePath()
                .normalize();
        CreateSpecService createSpecService = mock(CreateSpecService.class);
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        Path projectRoot = workspaceRoot.resolve("vue_project_1").toAbsolutePath().normalize();
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), anyString()))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-landing",
                        projectRoot.toString(),
                        1
                ));
        when(vueBootstrapService.resolveProjectRoot(anyLong())).thenReturn(projectRoot);
        CreateTemplateRuntime runtime = new CreateTemplateRuntime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                new CreatePreWriteValidationService(new com.rush.rushaicodemother.orchestration.codegraph.StructuredSyntaxValidationService()),
                createSpecService,
                new CreateRecipeRendererService(new LandingSlotFallbackRenderer()),
                null,
                patchApplyService,
                new LandingSlotFallbackRenderer(),
                vueBootstrapService
        );
        when(createSpecService.generate(anyString(), any()))
                .thenReturn(new CreateSpecService.SpecResult(true, fitnessSpec(), ""));

        SlotFillResult result = runtime.generate(app(), request(), plan());

        assertTrue(result.patchOperations().isEmpty());
        Map<?, ?> telemetry = (Map<?, ?>) result.metadata().get("telemetry");
        assertEquals(false, telemetry.get("fallback"));
        assertEquals(true, telemetry.get("degraded"));
        verify(patchApplyService, never()).applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString());
    }

    @Test
    void shouldCoalesceSlotGroupsByTemplateBeforeRenderingRecipe() {
        Path workspaceRoot = Path.of("target/test-workspaces/create-template-runtime")
                .toAbsolutePath()
                .normalize();
        CreateSpecService createSpecService = mock(CreateSpecService.class);
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        Path projectRoot = workspaceRoot.resolve("vue_project_1").toAbsolutePath().normalize();
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), anyString()))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-landing",
                        projectRoot.toString(),
                        1
                ));
        when(vueBootstrapService.resolveProjectRoot(anyLong())).thenReturn(projectRoot);
        CreateTemplateRuntime runtime = new CreateTemplateRuntime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                new CreatePreWriteValidationService(new com.rush.rushaicodemother.orchestration.codegraph.StructuredSyntaxValidationService()),
                createSpecService,
                new CreateRecipeRendererService(new LandingSlotFallbackRenderer()),
                null,
                patchApplyService,
                new LandingSlotFallbackRenderer(),
                vueBootstrapService
        );
        when(createSpecService.generate(anyString(), any()))
                .thenReturn(new CreateSpecService.SpecResult(true, fitnessSpec(), ""));
        when(patchApplyService.applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(PatchApplyResult.applied(1L, "task", projectRoot.toString(), 1, List.of("src/data/landingData.ts")));

        SlotFillResult result = runtime.generate(app(), request(), multiGroupPlan());

        assertEquals(1, result.filledSlotCount());
        Map<?, ?> telemetry = (Map<?, ?>) result.metadata().get("telemetry");
        assertEquals(1, telemetry.get("slotGroupCount"));
        assertEquals(1, telemetry.get("aiCallCount"));
        verify(createSpecService).generate(anyString(), any());
    }

    @Test
    void shouldGenerateOneCreateSpecForFullStackAndReuseAcrossGroups() {
        Path workspaceRoot = Path.of("target/test-workspaces/create-template-runtime/full-stack")
                .toAbsolutePath()
                .normalize();
        CreateSpecService createSpecService = mock(CreateSpecService.class);
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        BackendProjectTemplateBootstrapService backendBootstrapService = mock(BackendProjectTemplateBootstrapService.class);
        FullStackPortAllocator portAllocator = mock(FullStackPortAllocator.class);
        when(portAllocator.allocate(anyLong()))
                .thenReturn(FullStackGenerationContext.create(1L, 17001, 18001, workspaceRoot.toString().replace("\\", "/")));
        when(vueBootstrapService.bootstrapIfNecessary(any(Path.class), anyString()))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-admin",
                        workspaceRoot.resolve("frontend").toString(),
                        1
                ));
        when(backendBootstrapService.bootstrapIfNecessary(any(Path.class)))
                .thenReturn(BackendProjectTemplateBootstrapService.BootstrapResult.created(
                        "go-sqlite-backend-basic",
                        workspaceRoot.resolve("backend").toString(),
                        1
                ));
        when(createSpecService.generate(anyString(), any()))
                .thenReturn(new CreateSpecService.SpecResult(true, fitnessSpec(), ""));
        when(patchApplyService.applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(PatchApplyResult.applied(1L, "task", workspaceRoot.toString(), 10,
                        List.of("frontend/src/data/adminData.ts", "backend/internal/modules/course/model.go")));

        CreateTemplateRuntime runtime = new CreateTemplateRuntime(
                backendBootstrapService,
                new CreatePatchMergeService(),
                new CreatePreWriteValidationService(new com.rush.rushaicodemother.orchestration.codegraph.StructuredSyntaxValidationService()),
                createSpecService,
                new CreateRecipeRendererService(new LandingSlotFallbackRenderer()),
                portAllocator,
                patchApplyService,
                new LandingSlotFallbackRenderer(),
                vueBootstrapService
        );

        SlotFillResult result = runtime.generate(app(CodeGenTypeEnum.FULL_STACK_PROJECT),
                request("做一个健身房全栈后台", CodeGenTypeEnum.FULL_STACK_PROJECT), fullStackPlan());

        assertEquals(1, ((Map<?, ?>) result.metadata().get("telemetry")).get("aiCallCount"));
        assertTrue(result.patchOperations().stream().anyMatch(operation -> operation.relativePath().startsWith("frontend/")));
        assertTrue(result.patchOperations().stream().anyMatch(operation -> operation.relativePath().startsWith("backend/")));
        verify(createSpecService, times(1)).generate(anyString(), any());
    }

    @Test
    void shouldUseLocalSpecRecipeWhenAiSpecTimesOut() {
        Path workspaceRoot = Path.of("target/test-workspaces/create-template-runtime")
                .toAbsolutePath()
                .normalize();
        CreateSpecService createSpecService = new CreateSpecService(
                mock(com.rush.rushaicodemother.ai.AiCreateSpecServiceFactory.class),
                new CreateSpecNormalizer()
        );
        GenerationPatchApplyService patchApplyService = mock(GenerationPatchApplyService.class);
        VueProjectTemplateBootstrapService vueBootstrapService = mock(VueProjectTemplateBootstrapService.class);
        Path projectRoot = workspaceRoot.resolve("vue_project_1").toAbsolutePath().normalize();
        when(vueBootstrapService.bootstrapIfNecessary(anyLong(), anyString()))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-landing",
                        projectRoot.toString(),
                        1
                ));
        when(vueBootstrapService.resolveProjectRoot(anyLong())).thenReturn(projectRoot);
        CreateTemplateRuntime runtime = new CreateTemplateRuntime(
                mock(BackendProjectTemplateBootstrapService.class),
                new CreatePatchMergeService(),
                new CreatePreWriteValidationService(new com.rush.rushaicodemother.orchestration.codegraph.StructuredSyntaxValidationService()),
                createSpecService,
                new CreateRecipeRendererService(new LandingSlotFallbackRenderer()),
                null,
                patchApplyService,
                new LandingSlotFallbackRenderer(),
                vueBootstrapService
        );
        when(patchApplyService.applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(PatchApplyResult.applied(1L, "task", projectRoot.toString(), 1, List.of("src/data/landingData.ts")));

        SlotFillResult result = runtime.generate(app(), request(), landingDataPlan());

        assertEquals(1, result.filledSlotCount());
        assertEquals(1, result.patchOperationCount());
        Map<?, ?> telemetry = (Map<?, ?>) result.metadata().get("telemetry");
        assertEquals(false, telemetry.get("fallback"));
        assertEquals(false, telemetry.get("degraded"));
        verify(patchApplyService, times(1)).applyWithoutChangePlan(anyLong(), anyString(), any(), any(), anyString());
    }

    private App app() {
        return app(CodeGenTypeEnum.VUE_PROJECT);
    }

    private App app(CodeGenTypeEnum codeGenType) {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(codeGenType.getValue());
        return app;
    }

    private GenerationTaskRequest request() {
        return request("做一个企业官网");
    }

    private GenerationTaskRequest request(String message) {
        User user = new User();
        user.setId(2L);
        return new GenerationTaskRequest(app(), message, user);
    }

    private GenerationTaskRequest request(String message, CodeGenTypeEnum codeGenType) {
        User user = new User();
        user.setId(2L);
        return new GenerationTaskRequest(app(codeGenType), message, user);
    }

    private CreateSpec fitnessSpec() {
        return new CreateSpec(
                new CreateSpec.Product("landing", "fitness_saas", "FitPilot", "健身房运营人员", "提升门店运营效率"),
                List.of(new CreateSpec.ModuleSpec("course_crud", "课程管理", List.of("table", "form"))),
                List.of(new CreateSpec.EntitySpec("Course", "课程", List.of(
                        new CreateSpec.FieldSpec("title", "string", "课程名称", true, List.of()),
                        new CreateSpec.FieldSpec("coach", "string", "教练", true, List.of()),
                        new CreateSpec.FieldSpec("price", "decimal", "价格", false, List.of()),
                        new CreateSpec.FieldSpec("status", "enum", "状态", false, List.of("上架", "下架")),
                        new CreateSpec.FieldSpec("capacity", "integer", "容量", false, List.of())
                ), List.of(), List.of("list", "create", "update", "delete"))),
                new CreateSpec.Frontend(
                        "landing_scroll",
                        List.of("专业", "运营中台"),
                        "compact",
                        List.of("metric_cards", "data_table"),
                        List.of("筛选", "分页"),
                        List.of("指标卡", "趋势图"),
                        List.of("亮点", "案例", "流程", "价格", "FAQ"),
                        new CreateSpec.Theme("#2563eb", "#f97316", "#f8fafc", "8px", "light")
                ),
                new CreateSpec.Backend("rest", false, true, true, true, true,
                        List.of("createdAt", "updatedAt"), false, true, List.of("required"),
                        "standard_json", "course"),
                new CreateSpec.Database(List.of(), List.of("title", "status"), true, "append_sql_schema"),
                new CreateSpec.Content(
                        "professional energetic",
                        "健身房运营数据",
                        List.of("亮点", "案例", "流程", "价格", "FAQ"),
                        List.of("landing", "pricing", "faq"),
                        new CreateSpec.Landing(
                                "让健身房运营更轻盈",
                                "用课程排班、会员跟进和经营看板，把门店运营变成可持续增长。",
                                "预约演示",
                                "查看方案",
                                List.of("亮点", "案例", "流程", "价格", "FAQ"),
                        List.of(
                                new CreateSpec.Stat("42%", "私教转化提升"),
                                new CreateSpec.Stat("12h", "每周排课节省"),
                                new CreateSpec.Stat("180+", "门店使用")
                        ),
                        List.of(
                                new CreateSpec.TextBlock("私教排课", "自动整理教练档期、课程容量和会员预约状态。"),
                                new CreateSpec.TextBlock("会员跟进", "把体验课、续费提醒和沉睡会员唤醒放到同一张运营清单。"),
                                new CreateSpec.TextBlock("经营看板", "实时查看课程满班率、私教转化和门店收入趋势。"),
                                new CreateSpec.TextBlock("多门店协同", "统一管理不同门店的课程、教练和会员数据。")
                        ),
                        List.of(
                                new CreateSpec.TextBlock("精品健身工作室", "上线后体验课转私教率提升 42%。"),
                                new CreateSpec.TextBlock("连锁瑜伽门店", "排课沟通时间每周减少 12 小时。"),
                                new CreateSpec.TextBlock("综合运动中心", "用统一看板追踪课程收入和会员留存。")
                        ),
                        List.of("运营诊断", "数据导入", "门店上线", "增长复盘"),
                        List.of(
                                new CreateSpec.Plan("单店版", "¥1,999/月", "适合单门店快速数字化。", List.of("课程排班", "会员管理", "基础看板")),
                                new CreateSpec.Plan("连锁版", "¥6,999/月", "适合多门店统一运营。", List.of("多门店管理", "教练绩效", "转化漏斗")),
                                new CreateSpec.Plan("定制版", "按需报价", "适合复杂系统集成。", List.of("私有部署", "数据对接", "专属支持"))
                        ),
                        List.of(
                                new CreateSpec.Faq("可以导入现有会员吗？", "可以，首次上线会协助整理会员、课程和教练数据。"),
                                new CreateSpec.Faq("支持多门店吗？", "支持按门店、角色和区域查看不同运营数据。"),
                                new CreateSpec.Faq("教练能单独使用吗？", "可以为教练配置移动端排课和会员跟进视图。"),
                                new CreateSpec.Faq("多久可以上线？", "标准门店通常 3-5 个工作日完成初始化。")
                        ),
                                new CreateSpec.Contact("hello@fitpilot.example", "400-800-2026", "线上演示可预约")
                        )
                ),
                new CreateSpec.Constraints(true, List.of("package.json", "go.mod"),
                        List.of("no_script_html", "no_secret"), 4, 8)
        );
    }

    private CreateGenerationPlan plan() {
        return new CreateGenerationPlan(
                CodeGenTypeEnum.VUE_PROJECT,
                new CreateTemplateManifest("vue-web-landing", CodeGenTypeEnum.VUE_PROJECT, "landing"),
                List.of(),
                List.of(new SlotGroup("hero", "vue-web-landing", "base", List.of("landing_data"), 1)),
                0.9,
                "test",
                "test",
                ""
        );
    }

    private CreateGenerationPlan multiGroupPlan() {
        return new CreateGenerationPlan(
                CodeGenTypeEnum.VUE_PROJECT,
                new CreateTemplateManifest("vue-web-landing", CodeGenTypeEnum.VUE_PROJECT, "landing"),
                List.of(
                        new FeatureModuleManifest("landing-page", "Landing", "vue-web-landing", List.of("landing_core_data"), ""),
                        new FeatureModuleManifest("landing-contact", "Contact", "vue-web-landing", List.of("landing_core_data"), "")
                ),
                List.of(
                        new SlotGroup("landing-page-slots", "vue-web-landing", "landing-page", List.of("landing_core_data"), 0),
                        new SlotGroup("landing-contact-slots", "vue-web-landing", "landing-contact", List.of("landing_core_data"), 1)
                ),
                0.9,
                "test",
                "test",
                ""
        );
    }

    private CreateGenerationPlan landingDataPlan() {
        return new CreateGenerationPlan(
                CodeGenTypeEnum.VUE_PROJECT,
                new CreateTemplateManifest("vue-web-landing", CodeGenTypeEnum.VUE_PROJECT, "landing"),
                List.of(new FeatureModuleManifest("landing-page", "Landing", "vue-web-landing",
                        List.of("landing_core_data"), "")),
                List.of(new SlotGroup("landing-page-slots", "vue-web-landing", "landing-page",
                        List.of("landing_core_data"), 0)),
                0.9,
                "test",
                "test",
                ""
        );
    }

    private CreateGenerationPlan adminPlan() {
        return new CreateGenerationPlan(
                CodeGenTypeEnum.VUE_PROJECT,
                new CreateTemplateManifest("vue-web-admin", CodeGenTypeEnum.VUE_PROJECT, "admin"),
                List.of(new FeatureModuleManifest("admin-dashboard", "Admin", "vue-web-admin",
                        List.of("dashboard_content", "mock_data", "table_columns", "sidebar_menu", "statistics_cards"), "")),
                List.of(new SlotGroup("admin-dashboard-slots", "vue-web-admin", "admin-dashboard",
                        List.of("dashboard_content", "mock_data", "table_columns", "sidebar_menu", "statistics_cards"), 0)),
                0.9,
                "test",
                "test",
                ""
        );
    }

    private CreateGenerationPlan fullStackPlan() {
        return new CreateGenerationPlan(
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                new CreateTemplateManifest("full-stack-basic", CodeGenTypeEnum.FULL_STACK_PROJECT, "full stack"),
                List.of(
                        new FeatureModuleManifest("admin-dashboard", "Admin", "vue-web-admin",
                                List.of("dashboard_content", "mock_data", "table_columns", "sidebar_menu", "statistics_cards"), ""),
                        new FeatureModuleManifest("backend-crud", "Backend", "go-sqlite-backend-basic",
                                List.of("domain_contract", "module_model", "module_repository", "module_service",
                                        "module_handler", "database_schema", "module_import", "server_wiring"), "")
                ),
                List.of(
                        new SlotGroup("admin-dashboard-slots", "vue-web-admin", "admin-dashboard",
                                List.of("dashboard_content", "mock_data", "table_columns", "sidebar_menu", "statistics_cards"), 0),
                        new SlotGroup("backend-crud-slots", "go-sqlite-backend-basic", "backend-crud",
                                List.of("domain_contract", "module_model", "module_repository", "module_service",
                                        "module_handler", "database_schema", "module_import", "server_wiring"), 1)
                ),
                0.9,
                "test",
                "test",
                ""
        );
    }
}
