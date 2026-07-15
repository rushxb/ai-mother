package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.codegraph.StructuredSyntaxValidationService;
import com.rush.rushaicodemother.orchestration.create.recipe.CreateRecipeRendererTestFactory;
import com.rush.rushaicodemother.orchestration.create.recipe.RecipeRenderResult;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.patch.PatchApplyServiceTestFactory;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.template.TemplateServiceTestFixture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateRecipeRendererServiceTest {

    @Test
    void shouldRenderBasicVueRecipeFromCreateSpec() {
        CreateRecipeRendererService renderer = CreateRecipeRendererTestFactory.create();
        RecipeRenderResult result = renderer.render(
                "做一个课程展示应用",
                new SlotGroup("basic", "vue-web-basic", "basic",
                        List.of("home_content", "mock_data", "app_config", "navigation_items", "theme_tokens"), 0),
                spec()
        );

        assertTrue(result.available());
        assertEquals(5, result.patchOperations().size());
        assertTrue(result.patchOperations().stream().anyMatch(operation ->
                operation.relativePath().equals("src/data/siteData.ts")
                        && operation.content().contains("FitPilot")));
        assertTrue(result.patchOperations().stream().anyMatch(operation ->
                operation.relativePath().equals("src/views/HomeView.vue")
                        && operation.content().contains("SectionTitle")));
    }

    @Test
    void shouldRenderMobileRecipeFromCreateSpec() {
        CreateRecipeRendererService renderer = CreateRecipeRendererTestFactory.create();
        RecipeRenderResult result = renderer.render(
                "做一个移动端课程预约",
                new SlotGroup("mobile", "vue-web-mobile", "mobile",
                        List.of("home_content", "mock_data", "tabbar_config", "product_list", "theme_tokens"), 0),
                spec()
        );

        assertTrue(result.available());
        assertEquals(5, result.patchOperations().size());
        assertTrue(result.patchOperations().stream().anyMatch(operation ->
                operation.relativePath().equals("src/data/mock.ts")
                        && operation.content().contains("课程")));
        assertTrue(result.patchOperations().stream().anyMatch(operation ->
                operation.relativePath().equals("src/styles/mobile.css")
                        && operation.content().contains("#2563eb")));
    }

    @Test
    void shouldRenderBackendCrudRecipeFromCreateSpec() {
        CreateRecipeRendererService renderer = CreateRecipeRendererTestFactory.create();
        RecipeRenderResult result = renderer.render(
                "做一个课程管理后端",
                new SlotGroup("backend", "go-sqlite-backend-basic", "backend",
                        List.of("domain_contract", "module_model", "module_repository", "module_service",
                                "module_handler", "database_schema", "module_import", "server_wiring"), 0),
                spec()
        );

        assertTrue(result.available());
        assertEquals(8, result.patchOperations().size());
        assertTrue(result.patchOperations().stream().anyMatch(operation ->
                operation.relativePath().equals("internal/modules/course/model.go")
                        && operation.content().contains("type Course struct")));
        assertTrue(result.patchOperations().stream().anyMatch(operation ->
                PatchOperation.ACTION_GO_ADD_IMPORT.equals(operation.action())
                        && operation.newContent().equals("backend-template/internal/modules/course")));
        assertTrue(result.patchOperations().stream().anyMatch(operation ->
                operation.relativePath().equals("sql/schema.sql")
                        && operation.newContent().contains("create table if not exists courses")));

        CreatePreWriteValidationService validationService =
                new CreatePreWriteValidationService(new StructuredSyntaxValidationService());
        CreatePreWriteValidationService.ValidationResult validationResult =
                validationService.validate(result.patchOperations());
        assertTrue(validationResult.valid(), validationResult.errors().toString());
    }

    @Test
    void shouldUseFrontendSpecKnobsInAdminRecipeOutput() {
        CreateRecipeRendererService renderer = CreateRecipeRendererTestFactory.create();
        RecipeRenderResult result = renderer.render(
                "做一个课程运营后台",
                new SlotGroup("admin", "vue-web-admin", "admin",
                        List.of("dashboard_content", "mock_data", "table_columns", "sidebar_menu",
                                "statistics_cards", "operations_data", "activity_timeline", "theme_tokens"), 0),
                spec()
        );

        assertTrue(result.available());
        assertTrue(result.patchOperations().stream().anyMatch(operation ->
                operation.relativePath().equals("src/styles/theme.css")
                        && operation.content().contains("--dashboard-padding: 12px")
                        && operation.content().contains("styleKeywords: 专业, 运营中台")));
        assertTrue(result.patchOperations().stream().anyMatch(operation ->
                operation.relativePath().equals("src/data/adminData.ts")
                        && operation.content().contains("dashboard-compact")
                        && operation.content().contains("enabledInteractions")
                        && operation.content().contains("业务趋势")));
        assertTrue(result.patchOperations().stream().anyMatch(operation ->
                operation.relativePath().equals("src/data/table.columns.ts")
                        && operation.content().contains("sortable: true")
                        && operation.content().contains("filterable: true")));
    }

    @Test
    void shouldUseBackendSwitchesAndDatabaseIndexesInBackendRecipeOutput() {
        CreateSpec switchedSpec = specWithBackendOptions(false, false, true, false, true, true, true);
        CreateRecipeRendererService renderer = CreateRecipeRendererTestFactory.create();
        RecipeRenderResult result = renderer.render(
                "做一个课程管理后端",
                new SlotGroup("backend", "go-sqlite-backend-basic", "backend",
                        List.of("module_model", "module_repository", "module_service",
                                "module_handler", "database_schema"), 0),
                switchedSpec
        );

        assertTrue(result.available());
        String model = content(result, "internal/modules/course/model.go");
        String repository = content(result, "internal/modules/course/repository.go");
        String handler = content(result, "internal/modules/course/handler.go");
        String schema = newContent(result, "sql/schema.sql");

        assertTrue(model.contains("SortBy string"));
        assertTrue(model.contains("BatchDeleteRequest"));
        assertTrue(!model.contains("Keyword string"));
        assertTrue(repository.contains("delete from courses where id = ?"));
        assertTrue(!repository.contains("is_deleted"));
        assertTrue(repository.contains("safeOrderBy"));
        assertTrue(handler.contains("requireAuth"));
        assertTrue(handler.contains("/batch-delete"));
        assertTrue(handler.contains("/import"));
        assertTrue(handler.contains("/export"));
        assertTrue(schema.contains("create index if not exists idx_courses_title on courses (title);"));
        assertTrue(schema.contains("create index if not exists idx_courses_status on courses (status);"));
        assertTrue(!schema.contains("is_deleted integer"));
    }

    @Test
    void shouldCompileRenderedBackendRecipeWithGoTestWhenGoIsAvailable() throws Exception {
        Assumptions.assumeTrue(goAvailable(), "Go toolchain is not available in this environment");
        Path outputRoot = Path.of("target", "test-workspaces", "create-backend-go-test")
                .toAbsolutePath()
                .normalize();
        cn.hutool.core.io.FileUtil.del(outputRoot.toFile());
        TemplateServiceTestFixture fixture = new TemplateServiceTestFixture(outputRoot);
        fixture.backendBootstrapService().bootstrapIfNecessary(1L, CodeGenTypeEnum.BACKEND_PROJECT);
        Path root = outputRoot.resolve("backend_project_1");

        CreateRecipeRendererService renderer = CreateRecipeRendererTestFactory.create();
        RecipeRenderResult result = renderer.render(
                "做一个课程管理后端",
                new SlotGroup("backend", "go-sqlite-backend-basic", "backend",
                        List.of("domain_contract", "module_model", "module_repository", "module_service",
                                "module_handler", "database_schema", "module_import", "server_wiring"), 0),
                spec()
        );
        GenerationPatchApplyService patchApplyService = PatchApplyServiceTestFactory.create();
        var applyResult = patchApplyService.applyWithoutChangePlan(1L, "backend-go-test", root,
                result.patchOperations(), "backend_recipe_compile_test");
        assertEquals("applied", applyResult.status(), applyResult.reason() + ":" + applyResult.rejectedOperations());

        ProcessBuilder processBuilder = new ProcessBuilder("go", "test", "./...")
                .directory(root.toFile())
                .redirectErrorStream(true);
        configureGoCache(processBuilder);
        Process process = processBuilder.start();
        boolean finished = process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(finished, output);
        assertEquals(0, process.exitValue(), output);
    }

    private void configureGoCache(ProcessBuilder processBuilder) throws Exception {
        Path targetDirectory = Path.of("target").toAbsolutePath().normalize();
        Path buildCache = Files.createDirectories(targetDirectory.resolve("go-cache"));
        Path moduleCache = Files.createDirectories(targetDirectory.resolve("go-mod-cache"));
        Path goPath = Files.createDirectories(targetDirectory.resolve("go-path"));
        processBuilder.environment().put("GOCACHE", buildCache.toString());
        processBuilder.environment().put("GOMODCACHE", moduleCache.toString());
        processBuilder.environment().put("GOPATH", goPath.toString());
    }

    private boolean goAvailable() {
        try {
            Process process = new ProcessBuilder("go", "version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String content(RecipeRenderResult result, String path) {
        return result.patchOperations().stream()
                .filter(operation -> operation.relativePath().equals(path))
                .findFirst()
                .map(PatchOperation::content)
                .orElse("");
    }

    private String newContent(RecipeRenderResult result, String path) {
        return result.patchOperations().stream()
                .filter(operation -> operation.relativePath().equals(path))
                .findFirst()
                .map(PatchOperation::newContent)
                .orElse("");
    }

    private CreateSpec specWithBackendOptions(boolean pagination,
                                             boolean search,
                                             boolean sort,
                                             boolean softDelete,
                                             boolean authRequired,
                                             boolean importExport,
                                             boolean batchActions) {
        CreateSpec base = spec();
        return new CreateSpec(
                base.product(),
                base.modules(),
                base.entities(),
                base.frontend(),
                new CreateSpec.Backend("rest", authRequired, pagination, search, sort, softDelete,
                        List.of("createdAt", "updatedAt"), importExport, batchActions, List.of("required"),
                        "standard_json", "course"),
                new CreateSpec.Database(List.of(), List.of("title", "status"), softDelete, "append_sql_schema"),
                base.content(),
                base.constraints()
        );
    }

    private CreateSpec spec() {
        return new CreateSpec(
                new CreateSpec.Product("backend", "fitness_saas", "FitPilot", "健身房运营人员", "提升运营效率"),
                List.of(new CreateSpec.ModuleSpec("course_crud", "课程管理", List.of("table", "form", "search"))),
                List.of(new CreateSpec.EntitySpec("Course", "课程", List.of(
                        new CreateSpec.FieldSpec("title", "string", "课程名称", true, List.of()),
                        new CreateSpec.FieldSpec("coach", "string", "教练", true, List.of()),
                        new CreateSpec.FieldSpec("price", "decimal", "价格", false, List.of()),
                        new CreateSpec.FieldSpec("status", "enum", "状态", false, List.of("上架", "下架")),
                        new CreateSpec.FieldSpec("capacity", "integer", "容量", false, List.of())
                ), List.of(), List.of("list", "create", "update", "delete"))),
                new CreateSpec.Frontend(
                        "sidebar_dashboard",
                        List.of("专业", "运营中台"),
                        "compact",
                        List.of("metric_cards", "data_table"),
                        List.of("筛选", "分页", "排序"),
                        List.of("指标卡", "趋势图"),
                        List.of("工作台", "课程管理"),
                        new CreateSpec.Theme("#2563eb", "#f97316", "#f8fafc", "8px", "light")
                ),
                new CreateSpec.Backend("rest", false, true, true, true, true,
                        List.of("createdAt", "updatedAt"), false, true, List.of("required"),
                        "standard_json", "course"),
                new CreateSpec.Database(List.of(), List.of("title", "status"), true, "append_sql_schema"),
                new CreateSpec.Content("professional", "健身房课程数据", List.of("工作台", "课程管理"),
                        List.of("course_crud"), null),
                new CreateSpec.Constraints(true, List.of("package.json", "go.mod"),
                        List.of("no_script_html", "no_secret"), 4, 8)
        );
    }
}
