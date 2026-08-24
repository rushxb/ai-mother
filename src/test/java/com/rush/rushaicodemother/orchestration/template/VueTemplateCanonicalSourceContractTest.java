package com.rush.rushaicodemother.orchestration.template;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定非管理端 Vue 模板的单一 TypeScript 运行时事实。 */
class VueTemplateCanonicalSourceContractTest {

    private static final String PORTABLE_BACKEND_ADDRESS = "127.0.0.1:18000";
    private static final Pattern LOCAL_SOURCE_IMPORT = Pattern.compile(
            "(?:from\\s+|import\\s*\\(\\s*|import\\s+)[\\\"']([^\\\"']+)[\\\"']"
    );

    private static final List<String> JAVASCRIPT_SHADOW_ENTRIES = List.of(
            "vite.config.js",
            "src/main.js",
            "src/router/index.js",
            "src/router/routeFactory.js"
    );

    private final Path templateRoot = Path.of(
            System.getProperty("projectBaseDir"),
            "src", "main", "resources", "project-templates"
    );

    @ParameterizedTest(name = "{0} 只保留 TypeScript 运行时入口")
    @ValueSource(strings = {"vue-web-basic", "vue-web-mobile", "vue-web-landing"})
    void templateMustExposeOneCanonicalTypeScriptRuntimeGraph(String templateId) throws IOException {
        Path root = templateRoot.resolve(templateId);
        String index = Files.readString(root.resolve("index.html"), StandardCharsets.UTF_8);

        assertTrue(index.contains("/src/main.ts"),
                () -> templateId + " 的浏览器入口没有指向受保护的 main.ts");
        for (String relativePath : JAVASCRIPT_SHADOW_ENTRIES) {
            assertFalse(Files.exists(root.resolve(relativePath)),
                    () -> templateId + " 存在会遮蔽 TypeScript 实现的旧入口: " + relativePath);
        }
    }

    @ParameterizedTest(name = "{0} 的 recipe 数据只存在一个运行时实现")
    @CsvSource({
            "vue-web-basic,src/data/siteData",
            "vue-web-landing,src/data/landingData"
    })
    void recipeDataMustNotBeShadowedByJavascriptTwin(String templateId, String relativeStem) {
        Path root = templateRoot.resolve(templateId);

        assertTrue(Files.isRegularFile(root.resolve(relativeStem + ".ts")),
                () -> templateId + " 缺少 recipe 声明的 TypeScript 数据文件");
        assertFalse(Files.exists(root.resolve(relativeStem + ".js")),
                () -> templateId + " 的 JavaScript 数据文件会遮蔽 recipe 写入结果");
    }

    @ParameterizedTest(name = "{0} 的 Mock 只进入显式开发运行时")
    @ValueSource(strings = {"vue-web-basic", "vue-web-mobile", "vue-web-landing"})
    void canonicalRuntimeMustLoadMockOnlyInDevelopment(String templateId) throws IOException {
        String main = Files.readString(
                templateRoot.resolve(templateId).resolve("src/main.ts"),
                StandardCharsets.UTF_8
        );

        assertTrue(main.contains("import.meta.env.DEV"));
        assertTrue(main.contains("await import('./mocks')"));
        assertFalse(main.contains("import { setupMock } from './mocks'"));
    }

    @ParameterizedTest(name = "{0} 的路由清单引用缺失页面时必须失败")
    @ValueSource(strings = {"vue-web-basic", "vue-web-mobile", "vue-web-landing"})
    void routeManifestMustFailClosedWhenViewIsMissing(String templateId) throws IOException {
        String routeFactory = Files.readString(
                templateRoot.resolve(templateId).resolve("src/router/routeFactory.ts"),
                StandardCharsets.UTF_8
        );

        assertTrue(routeFactory.contains("Route component not found"),
                () -> templateId + " 会把缺失页面恢复成 undefined 路由");
        assertTrue(routeFactory.contains("throw new Error"));
    }

    @ParameterizedTest(name = "{0} 使用可静态部署的 Hash 路由")
    @ValueSource(strings = {"vue-web-basic", "vue-web-mobile", "vue-web-landing"})
    void portableTemplateMustKeepRefreshSafeRouting(String templateId) throws IOException {
        String router = Files.readString(
                templateRoot.resolve(templateId).resolve("src/router/index.ts"),
                StandardCharsets.UTF_8
        );

        assertTrue(router.contains("createWebHashHistory"),
                () -> templateId + " 使用 history 路由会让静态部署刷新返回 404");
        assertFalse(router.contains("createWebHistory"));
    }

    @ParameterizedTest(name = "{0} 的本地代理连接生成后端默认端口")
    @ValueSource(strings = {
            "vue-web-basic", "vue-web-mobile", "vue-web-landing", "vue-web-admin"
    })
    void developmentProxyMustReachGeneratedBackendPortableDefault(String templateId)
            throws IOException {
        String backendConfig = Files.readString(
                templateRoot.resolve("go-sqlite-backend-basic/internal/config/config.go"),
                StandardCharsets.UTF_8
        );
        String viteConfig = Files.readString(
                templateRoot.resolve(templateId).resolve("vite.config.ts"),
                StandardCharsets.UTF_8
        );

        assertTrue(backendConfig.contains("\":18000\""));
        assertTrue(viteConfig.contains("http://" + PORTABLE_BACKEND_ADDRESS),
                () -> templateId + " 的 /api 代理没有连接生成后端默认端口");
    }

    @ParameterizedTest(name = "{0} 的本地源码引用必须能解析")
    @ValueSource(strings = {"vue-web-basic", "vue-web-mobile", "vue-web-landing"})
    void canonicalSourceGraphMustNotReferenceMissingFiles(String templateId) throws IOException {
        Path root = templateRoot.resolve(templateId);
        try (Stream<Path> sourceFiles = Files.walk(root.resolve("src"))) {
            for (Path sourceFile : sourceFiles.filter(this::isSourceFile).toList()) {
                assertLocalImportsResolve(root, sourceFile);
            }
        }
    }

    @ParameterizedTest(name = "{0} 的实验性 3D 示例不进入生产构建图")
    @ValueSource(strings = {"vue-web-basic", "vue-web-mobile"})
    void unfinishedThreeDimensionalExamplesMustStayOutsideProductionBuild(String templateId)
            throws IOException {
        Path root = templateRoot.resolve(templateId);
        String tsconfig = Files.readString(root.resolve("tsconfig.json"), StandardCharsets.UTF_8);
        String componentIndex = Files.readString(
                root.resolve("src/components/index.ts"), StandardCharsets.UTF_8);

        assertTrue(tsconfig.contains("src/components/tres/**/*"));
        assertFalse(componentIndex.contains("export * from './tres'"));
    }

    private boolean isSourceFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".ts") || fileName.endsWith(".vue");
    }

    private void assertLocalImportsResolve(Path root, Path sourceFile) throws IOException {
        Matcher matcher = LOCAL_SOURCE_IMPORT.matcher(
                Files.readString(sourceFile, StandardCharsets.UTF_8));
        while (matcher.find()) {
            String importedPath = matcher.group(1);
            if (!importedPath.startsWith("@/")
                    && !importedPath.startsWith("./")
                    && !importedPath.startsWith("../")) {
                continue;
            }
            Path unresolved = importedPath.startsWith("@/")
                    ? root.resolve("src").resolve(importedPath.substring(2))
                    : sourceFile.getParent().resolve(importedPath);
            assertTrue(sourceTargetExists(unresolved.normalize()),
                    () -> root.getFileName() + " 的 " + root.relativize(sourceFile)
                            + " 引用了不存在的源码: " + importedPath);
        }
    }

    private boolean sourceTargetExists(Path unresolved) {
        if (Files.exists(unresolved)) {
            return true;
        }
        return Stream.of(".ts", ".vue", ".js", ".json", ".css")
                        .map(suffix -> Path.of(unresolved + suffix))
                        .anyMatch(Files::isRegularFile)
                || Stream.of("index.ts", "index.vue", "index.js")
                        .map(unresolved::resolve)
                        .anyMatch(Files::isRegularFile);
    }
}
