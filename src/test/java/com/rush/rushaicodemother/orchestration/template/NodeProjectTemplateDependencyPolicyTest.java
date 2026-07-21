package com.rush.rushaicodemother.orchestration.template;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeProjectTemplateDependencyPolicyTest {

    private static final List<String> NODE_TEMPLATE_IDS = List.of(
            "vue-web-basic",
            "vue-web-admin",
            "vue-web-mobile",
            "vue-web-landing"
    );

    @Test
    void nodeTemplatesMustUseExactVersionsAndCommittedPnpmLockfiles() throws IOException {
        Path templatesRoot = Path.of(System.getProperty("projectBaseDir"),
                "src", "main", "resources", "project-templates");

        for (String templateId : NODE_TEMPLATE_IDS) {
            Path templateRoot = templatesRoot.resolve(templateId);
            Path packageJsonPath = templateRoot.resolve("package.json");
            Path lockfilePath = templateRoot.resolve("pnpm-lock.yaml");

            assertTrue(Files.isRegularFile(packageJsonPath), templateId + " must contain package.json");
            assertTrue(Files.isRegularFile(lockfilePath), templateId + " must contain pnpm-lock.yaml");
            assertFalse(Files.readString(lockfilePath, StandardCharsets.UTF_8).isBlank(),
                    templateId + " lockfile must not be empty");

            JSONObject packageJson = JSONUtil.readJSONObject(packageJsonPath.toFile(), StandardCharsets.UTF_8);
            assertEquals("pnpm@9.15.4", packageJson.getStr("packageManager"),
                    templateId + " must pin the supported package manager");
            assertExactVersions(templateId, packageJson.getJSONObject("dependencies"));
            assertExactVersions(templateId, packageJson.getJSONObject("devDependencies"));
        }
    }

    private void assertExactVersions(String templateId, JSONObject dependencies) {
        if (dependencies == null) {
            return;
        }
        for (Map.Entry<String, Object> dependency : dependencies.entrySet()) {
            String version = String.valueOf(dependency.getValue());
            assertTrue(version.matches("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?"),
                    () -> templateId + " dependency " + dependency.getKey()
                            + " must use an exact version, but was " + version);
        }
    }
}
