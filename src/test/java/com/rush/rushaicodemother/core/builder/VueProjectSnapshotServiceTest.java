package com.rush.rushaicodemother.core.builder;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VueProjectSnapshotServiceTest {

    @TempDir
    Path projectRoot;

    @Test
    void shouldIsolateDependencyCriticalAndPresentationChanges() throws Exception {
        VueProjectSnapshotService service = serviceWithDefaults();
        JSONObject packageJson = packageJson("1.0.0", "vite build");
        write("package.json", packageJson.toString());
        write("pnpm-lock.yaml", "lockfileVersion: 9");
        write("src/main.ts", "export const version = 1");
        write("src/App.vue", "<template>one</template>");
        VueProjectSnapshot initial = service.capture(projectRoot, packageJson);

        write("src/App.vue", "<template>two</template>");
        VueProjectSnapshot presentationChanged = service.capture(projectRoot, packageJson);
        assertEquals(initial.dependencyFingerprint(), presentationChanged.dependencyFingerprint());
        assertEquals(initial.criticalFingerprint(), presentationChanged.criticalFingerprint());
        assertNotEquals(initial.presentationFingerprint(), presentationChanged.presentationFingerprint());

        write("src/main.ts", "export const version = 2");
        VueProjectSnapshot criticalChanged = service.capture(projectRoot, packageJson);
        assertEquals(presentationChanged.dependencyFingerprint(), criticalChanged.dependencyFingerprint());
        assertNotEquals(presentationChanged.criticalFingerprint(), criticalChanged.criticalFingerprint());
        assertEquals(presentationChanged.presentationFingerprint(), criticalChanged.presentationFingerprint());

        write("pnpm-lock.yaml", "lockfileVersion: 9\nrevision: 2");
        VueProjectSnapshot dependencyChanged = service.capture(projectRoot, packageJson);
        assertNotEquals(criticalChanged.dependencyFingerprint(), dependencyChanged.dependencyFingerprint());
        assertEquals(criticalChanged.criticalFingerprint(), dependencyChanged.criticalFingerprint());
        assertEquals(criticalChanged.presentationFingerprint(), dependencyChanged.presentationFingerprint());
    }

    @Test
    void shouldIgnoreGeneratedDirectoriesAndCanonicalizePackageSections() throws Exception {
        VueProjectSnapshotService service = serviceWithDefaults();
        JSONObject firstPackageJson = JSONUtil.parseObj("""
                {
                  "packageManager":"pnpm@9.0.0",
                  "dependencies":{"z":"1.0.0","a":"2.0.0"},
                  "scripts":{"build":"vite build"}
                }
                """);
        JSONObject reorderedPackageJson = JSONUtil.parseObj("""
                {
                  "scripts":{"build":"vite build"},
                  "dependencies":{"a":"2.0.0","z":"1.0.0"},
                  "packageManager":"pnpm@9.0.0"
                }
                """);
        write("package.json", firstPackageJson.toString());
        write("src/main.ts", "export {};");
        write("dist/assets/index.js", "old-dist");
        write("node_modules/example/index.js", "old-module");
        VueProjectSnapshot initial = service.capture(projectRoot, firstPackageJson);

        write("dist/assets/index.js", "new-dist");
        write("node_modules/example/index.js", "new-module");
        VueProjectSnapshot afterGeneratedChanges = service.capture(projectRoot, reorderedPackageJson);

        assertEquals(initial, afterGeneratedChanges);
    }

    @Test
    void packageManagerShouldInvalidateDependenciesWhileScriptsInvalidateCriticalSources() throws Exception {
        VueProjectSnapshotService service = serviceWithDefaults();
        JSONObject initialPackage = packageJson("9.0.0", "vite build");
        JSONObject managerChanged = packageJson("10.0.0", "vite build");
        JSONObject scriptChanged = packageJson("10.0.0", "vite build --mode production");
        write("package.json", initialPackage.toString());
        VueProjectSnapshot initial = service.capture(projectRoot, initialPackage);
        VueProjectSnapshot dependencyChanged = service.capture(projectRoot, managerChanged);
        VueProjectSnapshot criticalChanged = service.capture(projectRoot, scriptChanged);

        assertNotEquals(initial.dependencyFingerprint(), dependencyChanged.dependencyFingerprint());
        assertEquals(initial.criticalFingerprint(), dependencyChanged.criticalFingerprint());
        assertEquals(dependencyChanged.dependencyFingerprint(), criticalChanged.dependencyFingerprint());
        assertNotEquals(dependencyChanged.criticalFingerprint(), criticalChanged.criticalFingerprint());
    }

    @Test
    void shouldRejectProjectsBeyondConfiguredScanLimits() throws Exception {
        WorkspaceFileSystemProperties properties = new WorkspaceFileSystemProperties();
        properties.setMaxFiles(2);
        properties.setMaxFileBytes(4);
        VueProjectSnapshotService service = new VueProjectSnapshotService(properties);
        JSONObject packageJson = packageJson("9.0.0", "vite build");
        write("package.json", packageJson.toString());
        write("src/one.ts", "1");
        write("src/two.ts", "2");

        IOException fileCountException = assertThrows(
                IOException.class,
                () -> service.capture(projectRoot, packageJson)
        );
        assertEquals("Vue 项目文件数量超过快照上限: 2", fileCountException.getMessage());

        properties.setMaxFiles(10);
        write("src/large.ts", "12345");
        IOException fileSizeException = assertThrows(
                IOException.class,
                () -> service.capture(projectRoot, packageJson)
        );
        assertEquals("Vue 项目文件超过快照大小上限: src/large.ts", fileSizeException.getMessage());
    }

    @Test
    void shouldNotFollowSymbolicLinksOutsideProjectRoot() throws Exception {
        VueProjectSnapshotService service = serviceWithDefaults();
        JSONObject packageJson = packageJson("9.0.0", "vite build");
        write("package.json", packageJson.toString());
        Path outsideFile = projectRoot.getParent().resolve("outside-source.ts");
        Files.writeString(outsideFile, "outside-one", StandardCharsets.UTF_8);
        Path link = projectRoot.resolve("src/linked.ts");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outsideFile);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "当前平台不支持创建符号链接: " + exception.getMessage());
        }
        VueProjectSnapshot initial = service.capture(projectRoot, packageJson);

        Files.writeString(outsideFile, "outside-two", StandardCharsets.UTF_8);
        VueProjectSnapshot changedOutside = service.capture(projectRoot, packageJson);

        assertEquals(initial, changedOutside);
    }

    private VueProjectSnapshotService serviceWithDefaults() {
        return new VueProjectSnapshotService(new WorkspaceFileSystemProperties());
    }

    private JSONObject packageJson(String packageManagerVersion, String buildScript) {
        return JSONUtil.parseObj("""
                {
                  "packageManager":"pnpm@%s",
                  "dependencies":{"vue":"3.5.0"},
                  "scripts":{"build":"%s"}
                }
                """.formatted(packageManagerVersion, buildScript));
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
