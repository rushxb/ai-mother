package com.rush.rushaicodemother.orchestration.agent;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipeLibrary;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkill;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkillLibrary;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationAgentSupportTest {

    private final GenerationAgentSupport support = new GenerationAgentSupport();

    @Test
    void shouldSelectIntentRelevantFilesWithinFileBudget() throws Exception {
        Path tempDir = createTempWorkspace();
        try {
            write(tempDir, "package.json", "{\"dependencies\":{\"vue\":\"latest\"}}");
            write(tempDir, "index.html", "<div id=\"app\"></div>");
            write(tempDir, "src/main.ts", "import { createApp } from 'vue'");
            write(tempDir, "src/App.vue", "<template><RouterView /></template>");
            write(tempDir, "src/router/index.ts", "export const routes = []");
            write(tempDir, "src/views/Login.vue", """
                    <template>login</template>
                    <script setup>
                    export function submitLoginForm() {}
                    </script>
                    """);
            write(tempDir, "src/views/Dashboard.vue", "<template>dashboard</template>");
            write(tempDir, "src/components/AuthPanel.vue", "<template>auth</template>");
            write(tempDir, "src/components/ChartPanel.vue", "<template>chart</template>");
            write(tempDir, "src/styles/theme.css", "body { color: #111; }");

            GenerationAgentSupport.ProjectContextPackage contextPackage = support.buildProjectContextPackage(
                    app(),
                    CodeGenTypeEnum.VUE_PROJECT,
                    "优化登录页和权限入口",
                    tempDir.toFile()
            );

            assertEquals("auth", contextPackage.intent());
            assertTrue(contextPackage.selectedFiles().size() <= 6);
            assertTrue(contextPackage.selectedFiles().contains("src/views/Login.vue"));
            assertTrue(contextPackage.projectContext().contains("src/views/Login.vue"));
            assertTrue(contextPackage.indexedSymbolCount() > 0);
            assertTrue(contextPackage.indexHits().stream()
                    .anyMatch(hit -> "src/views/Login.vue".equals(hit.get("relativePath"))));
            assertTrue(contextPackage.projectContext().contains("索引命中"));
            assertEquals("intent_selected_files", contextPackage.contextMode());
        } finally {
            cleanup(tempDir);
        }
    }

    @Test
    void shouldInferNavigationAndFormIntents() throws Exception {
        Path tempDir = createTempWorkspace();
        try {
            write(tempDir, "src/router/index.ts", "export const routes = []");
            write(tempDir, "src/layouts/AppLayout.vue", "<template><router-view /></template>");
            write(tempDir, "src/components/UserForm.vue", "<template>form</template>");

            GenerationAgentSupport.ProjectContextPackage navigationPackage = support.buildProjectContextPackage(
                    app(),
                    CodeGenTypeEnum.VUE_PROJECT,
                    "调整路由和侧边栏布局",
                    tempDir.toFile()
            );

            assertEquals("navigation", navigationPackage.intent());
            assertTrue(navigationPackage.projectContext().contains("src/router/index.ts"));

            GenerationAgentSupport.ProjectContextPackage formPackage = support.buildProjectContextPackage(
                    app(),
                    CodeGenTypeEnum.VUE_PROJECT,
                    "优化表单交互和弹窗",
                    tempDir.toFile()
            );

            assertEquals("form", formPackage.intent());
            assertTrue(formPackage.projectContext().contains("src/components/UserForm.vue"));
        } finally {
            cleanup(tempDir);
        }
    }

    @Test
    void shouldTruncateSelectedFileContent() throws Exception {
        Path tempDir = createTempWorkspace();
        try {
            write(tempDir, "index.html", "a".repeat(3000));

            GenerationAgentSupport.ProjectContextPackage contextPackage = support.buildProjectContextPackage(
                    app(),
                    CodeGenTypeEnum.HTML,
                    "更新首页",
                    tempDir.toFile()
            );

            assertTrue(contextPackage.projectContext().contains("文件内容过长"));
            assertTrue(contextPackage.projectContext().length() < 1800);
        } finally {
            cleanup(tempDir);
        }
    }

    @Test
    void shouldMatchRecipeAndSelectDatabaseContextHints() throws Exception {
        Path tempDir = createTempWorkspace();
        try {
            write(tempDir, "backend/main.go", "package main");
            write(tempDir, "backend/schema.sql", "create table users(id integer primary key);");
            write(tempDir, "src/api/database.ts", "export function listUsers() {}");

            GenerationAgentSupport.ProjectContextPackage contextPackage = support.buildProjectContextPackage(
                    app(),
                    CodeGenTypeEnum.VUE_PROJECT,
                    "接入 database 后端接口和 sqlite 数据服务",
                    tempDir.toFile()
            );

            List<Map<String, Object>> recipes = support.buildRecipePayloads(
                    support.matchRecipes("接入 database 后端接口和 sqlite 数据服务", contextPackage.projectContext())
            );

            assertEquals("database", contextPackage.intent());
            assertTrue(contextPackage.selectedFiles().contains("backend/main.go"));
            assertTrue(recipes.stream().anyMatch(recipe -> "database-service".equals(recipe.get("id"))));
        } finally {
            cleanup(tempDir);
        }
    }

    @Test
    void shouldMatchSkillAndSelectSkillContextHints() throws Exception {
        Path workspace = createTempWorkspace();
        Path skillRoot = createTempWorkspace();
        try {
            write(workspace, "src/layouts/AdminLayout.vue", "<template>admin layout</template>");
            write(workspace, "src/router/index.ts", "export const routes = []");
            write(skillRoot, "admin-dashboard/SKILL.md", """
                    ---
                    name: Admin Dashboard Skill
                    description: Admin dashboard generation guidance.
                    keywords: 后台,dashboard,仪表盘
                    modules: dashboard,navigation
                    contextFileHints: src/layouts,src/router
                    implementationHints: 路由和菜单同步修改
                    validationHints: 验证菜单跳转
                    ---
                    - 页面、路由和菜单要一起改。
                    """);
            GenerationAgentSupport customSupport = new GenerationAgentSupport(
                    new GenerationRecipeLibrary(),
                    new GenerationSkillLibrary(skillRoot),
                    new WorkspaceSemanticIndexService(),
                    workspace
            );

            GenerationAgentSupport.ProjectContextPackage contextPackage = customSupport.buildProjectContextPackage(
                    app(),
                    CodeGenTypeEnum.VUE_PROJECT,
                    "创建后台 dashboard 仪表盘",
                    workspace.toFile()
            );
            List<GenerationSkill> skills = customSupport.matchSkills("创建后台 dashboard 仪表盘");
            List<Map<String, Object>> payloads = customSupport.buildSkillPayloads(skills);

            assertTrue(contextPackage.selectedFiles().contains("src/layouts/AdminLayout.vue"));
            assertTrue(contextPackage.selectedFiles().contains("src/router/index.ts"));
            assertEquals(1, skills.size());
            assertEquals("admin-dashboard-skill", skills.getFirst().id());
            assertTrue(payloads.stream().anyMatch(payload -> "Admin Dashboard Skill".equals(payload.get("title"))));
        } finally {
            cleanup(workspace);
            cleanup(skillRoot);
        }
    }

    @Test
    void shouldReturnEmptySelectionForBlankArtifacts() {
        List<String> normalized = support.normalizeSelectedFiles(Arrays.asList("  ", null, "../secret", "src/App.vue"));
        assertTrue(normalized.contains("src/App.vue"));
        assertEquals(1, normalized.size());
    }

    private App app() {
        App app = new App();
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        return app;
    }

    private Path createTempWorkspace() throws Exception {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ai-code-mother-tests");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "generation-agent-support-");
    }

    private void write(Path rootDir, String relativePath, String content) throws Exception {
        Path file = rootDir.resolve(relativePath);
        Files.createDirectories(file.getParent() == null ? rootDir : file.getParent());
        Files.writeString(file, content);
    }

    private void cleanup(Path path) {
        if (path == null) {
            return;
        }
        try {
            FileUtil.del(path.toFile());
        } catch (Exception ignored) {
        }
    }
}
