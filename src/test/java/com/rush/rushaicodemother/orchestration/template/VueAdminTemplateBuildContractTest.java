package com.rush.rushaicodemother.orchestration.template;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定管理后台模板的单一 TypeScript 入口与生产构建边界。 */
class VueAdminTemplateBuildContractTest {

    private final Path templateRoot = Path.of(
            System.getProperty("projectBaseDir"),
            "src", "main", "resources", "project-templates", "vue-web-admin"
    );

    @Test
    void adminTemplateMustNotKeepAmbiguousJavascriptEntryPoints() throws IOException {
        String index = read("index.html");
        assertTrue(index.contains("/src/main.ts"));

        List<String> removedLegacyEntries = List.of(
                "vite.config.js",
                "src/main.js",
                "src/data/adminData.js",
                "src/router/index.js",
                "src/router/routeFactory.js"
        );
        for (String relativePath : removedLegacyEntries) {
            assertFalse(Files.exists(templateRoot.resolve(relativePath)),
                    () -> "存在会遮蔽 TypeScript 实现的旧入口: " + relativePath);
        }
    }

    @Test
    void productionRouteAndMockBoundariesMustFailClosed() throws IOException {
        String routeFactory = read("src/router/routeFactory.ts");
        assertTrue(routeFactory.contains("'redirect' in item"));
        assertTrue(routeFactory.contains("Route component is not registered"));
        assertFalse(routeFactory.contains("import.meta.glob"));

        String main = read("src/main.ts");
        assertTrue(main.contains("import.meta.env.DEV"));
        assertTrue(main.contains("await import('./mocks')"));
        assertFalse(main.contains("import { setupMock } from './mocks'"));
    }

    @Test
    void unfinishedThreeDimensionalExamplesMustStayOutsideProductionBuild() throws IOException {
        assertTrue(read("tsconfig.json").contains("src/components/tres/**/*"));
        assertFalse(read("src/components/index.ts").contains("export * from './tres'"));
        assertTrue(read("src/components/ui/label/Label.vue").contains(":for=\"props.for\""));
    }

    private String read(String relativePath) throws IOException {
        return Files.readString(templateRoot.resolve(relativePath), StandardCharsets.UTF_8);
    }
}
