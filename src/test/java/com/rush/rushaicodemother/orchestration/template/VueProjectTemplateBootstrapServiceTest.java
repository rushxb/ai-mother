package com.rush.rushaicodemother.orchestration.template;

import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueProjectTemplateBootstrapServiceTest {

    @Test
    void shouldSelectAdminTemplateForDashboardPrompt() {
        VueProjectTemplateBootstrapService service = new VueProjectTemplateBootstrapService(
                Path.of("target", "test-workspaces", "template-bootstrap", "select-admin"),
                new PathMatchingResourcePatternResolver()
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
                new PathMatchingResourcePatternResolver()
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
}
