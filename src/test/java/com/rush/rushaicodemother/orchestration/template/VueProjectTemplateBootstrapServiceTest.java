package com.rush.rushaicodemother.orchestration.template;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VueProjectTemplateBootstrapServiceTest {

    @Test
    void shouldSelectAdminTemplateForDashboardPrompt() {
        VueProjectTemplateBootstrapService service = new VueProjectTemplateBootstrapService(
                Path.of("target", "test-workspaces", "template-bootstrap", "select-admin"),
                new PathMatchingResourcePatternResolver(),
                new TemplatePreWarmService()
        );

        assertEquals("vue-web-admin", service.selectTemplateId("创建一个 Vue 后台管理仪表盘"));
        assertEquals("vue-web-mobile", service.selectTemplateId("做一个移动端 H5 商城"));
        assertEquals("vue-web-landing", service.selectTemplateId("生成一个产品官网落地页"));
        assertEquals("vue-web-basic", service.selectTemplateId("做一个通用工具应用"));
    }

    @Test
    void shouldCopyTemplateIntoVueWorkspace() throws Exception {
        Path outputRoot = Path.of("target", "test-workspaces", "template-bootstrap", "copy-template");
        FileUtil.del(outputRoot.toFile());
        VueProjectTemplateBootstrapService service = new VueProjectTemplateBootstrapService(
                outputRoot,
                new PathMatchingResourcePatternResolver(),
                new TemplatePreWarmService()
        );

        VueProjectTemplateBootstrapService.BootstrapResult result =
                service.bootstrapIfNecessary(101L, "创建一个 Vue 后台管理仪表盘");

        Path workspace = outputRoot.resolve("vue_project_101");
        assertTrue(result.bootstrapped());
        assertEquals("vue-web-admin", result.templateId());
        assertTrue(result.fileCount() > 0);
        assertTrue(Files.exists(workspace.resolve("package.json")));
        assertTrue(Files.exists(workspace.resolve("src/views/DashboardView.vue")));
        assertTrue(Files.exists(workspace.resolve("src/router/routeManifest.json")));
    }

    @Test
    void bootstrapFailureMustHideInternalDetailsPreserveCauseAndRemovePartialWorkspace() throws Exception {
        Path outputRoot = Path.of("target", "test-workspaces", "template-bootstrap", "vue-failure");
        FileUtil.del(outputRoot.toFile());
        PathMatchingResourcePatternResolver resolver = mock(PathMatchingResourcePatternResolver.class);
        IOException failure = new IOException("sensitive-resource-path=C:/internal/templates/vue");
        when(resolver.getResources("classpath:project-templates/vue-web-basic/**/*"))
                .thenThrow(failure);
        VueProjectTemplateBootstrapService service = new VueProjectTemplateBootstrapService(
                outputRoot,
                resolver,
                new TemplatePreWarmService()
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.bootstrapIfNecessary(102L, "generic application")
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertEquals("初始化 Vue 项目模板失败，请稍后重试", exception.getMessage());
        assertFalse(exception.getMessage().contains("sensitive-resource-path"));
        assertSame(failure, exception.getCause());
        assertFalse(Files.exists(outputRoot.resolve("vue_project_102")));
    }
}
