package com.rush.rushaicodemother.orchestration.template;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VueProjectTemplateBootstrapServiceTest {

    @Test
    void shouldSelectKnownVueTemplates() {
        TemplateServiceTestFixture fixture = new TemplateServiceTestFixture(testRoot("select"));
        VueProjectTemplateBootstrapService service = fixture.vueBootstrapService();

        assertEquals(ProjectTemplateCatalog.VUE_ADMIN, service.selectTemplateId("创建一个 Vue 后台管理仪表盘"));
        assertEquals(ProjectTemplateCatalog.VUE_MOBILE, service.selectTemplateId("做一个移动端 H5 商城"));
        assertEquals(ProjectTemplateCatalog.VUE_LANDING, service.selectTemplateId("生成一个产品官网落地页"));
        assertEquals(ProjectTemplateCatalog.VUE_BASIC, service.selectTemplateId("做一个通用工具应用"));
    }

    @Test
    void shouldAtomicallyCopyTemplateIntoCanonicalVueWorkspace() throws Exception {
        Path outputRoot = resetRoot("copy-template");
        VueProjectTemplateBootstrapService service = new TemplateServiceTestFixture(outputRoot).vueBootstrapService();

        VueProjectTemplateBootstrapService.BootstrapResult result = service.bootstrapIfNecessary(
                101L,
                CodeGenTypeEnum.VUE_PROJECT,
                "创建一个 Vue 后台管理仪表盘"
        );

        Path workspace = outputRoot.resolve("vue_project_101").toAbsolutePath().normalize();
        assertTrue(result.bootstrapped());
        assertEquals(ProjectTemplateCatalog.VUE_ADMIN, result.templateId());
        assertTrue(result.fileCount() > 0);
        assertEquals(workspace.toString(), result.projectPath());
        assertTrue(Files.isRegularFile(workspace.resolve("package.json")));
        assertTrue(Files.isRegularFile(workspace.resolve("src/views/DashboardView.vue")));
        assertTrue(Files.isRegularFile(workspace.resolve("src/router/routeManifest.json")));
        try (var entries = Files.newDirectoryStream(outputRoot, ".vue_project_101.template-*")) {
            assertFalse(entries.iterator().hasNext());
        }
    }

    @Test
    void bootstrapFailureMustHideDetailsPreserveCauseAndLeaveNoPublishedWorkspace() throws Exception {
        Path outputRoot = resetRoot("failure");
        PathMatchingResourcePatternResolver resolver = mock(PathMatchingResourcePatternResolver.class);
        IOException failure = new IOException("sensitive-resource-path=C:/internal/templates/vue");
        when(resolver.getResources("classpath:project-templates/vue-web-basic/**/*")).thenThrow(failure);
        TemplateServiceTestFixture fixture = new TemplateServiceTestFixture(
                outputRoot,
                resolver,
                new com.rush.rushaicodemother.config.TemplateMaterializationProperties()
        );
        VueProjectTemplateBootstrapService service = fixture.vueBootstrapService();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.bootstrapIfNecessary(102L, CodeGenTypeEnum.VUE_PROJECT, "generic application")
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertEquals("初始化 Vue 项目模板失败，请稍后重试", exception.getMessage());
        assertFalse(exception.getMessage().contains("sensitive-resource-path"));
        assertTrue(exception.getCause() instanceof TemplateMaterializationException);
        assertSame(failure, exception.getCause().getCause());
        assertFalse(Files.exists(outputRoot.resolve("vue_project_102")));
    }

    @Test
    void concurrentBootstrapMustConvergeToOneCompleteWorkspace() throws Exception {
        Path outputRoot = resetRoot("concurrent");
        VueProjectTemplateBootstrapService service = new TemplateServiceTestFixture(outputRoot).vueBootstrapService();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return service.bootstrapIfNecessary(104L, CodeGenTypeEnum.VUE_PROJECT, "generic application");
            });
            var second = executor.submit(() -> {
                start.await();
                return service.bootstrapIfNecessary(104L, CodeGenTypeEnum.VUE_PROJECT, "generic application");
            });
            start.countDown();

            List<VueProjectTemplateBootstrapService.BootstrapResult> results = List.of(first.get(), second.get());
            assertEquals(1, results.stream().filter(VueProjectTemplateBootstrapService.BootstrapResult::bootstrapped).count());
            assertEquals(1, results.stream().filter(result -> "workspace_exists".equals(result.reason())).count());
        }
        Path workspace = outputRoot.resolve("vue_project_104");
        assertTrue(Files.isRegularFile(workspace.resolve("package.json")));
        assertTrue(Files.isRegularFile(workspace.resolve("src/App.vue")));
    }
    @Test
    void shouldRejectUnsafeExistingWorkspaceInsteadOfTreatingItAsInitialized() throws Exception {
        Path outputRoot = resetRoot("unsafe-existing");
        Files.createDirectories(outputRoot);
        Files.writeString(outputRoot.resolve("vue_project_103"), "not-a-directory");
        VueProjectTemplateBootstrapService service = new TemplateServiceTestFixture(outputRoot).vueBootstrapService();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.bootstrapIfNecessary(103L, CodeGenTypeEnum.VUE_PROJECT, "generic application")
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
    }

    private Path resetRoot(String name) {
        Path root = testRoot(name);
        FileUtil.del(root.toFile());
        return root;
    }

    private Path testRoot(String name) {
        return Path.of("target", "test-workspaces", "template-bootstrap", "vue-" + name);
    }
}