package com.rush.rushaicodemother.orchestration.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationSkillLibraryTest {

    @Test
    void shouldMatchSkillAndBuildPayloadsFromMarkdown() throws Exception {
        Path root = Files.createTempDirectory("skill-library-test");
        try {
            Path skillDir = root.resolve("vue-admin");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: Vue Admin Dashboard
                    description: Keep admin-style Vue generation consistent.
                    keywords: 后台,管理系统,dashboard,仪表盘
                    modules: dashboard,navigation
                    contextFileHints: src/layouts,src/router
                    implementationHints: 复用布局骨架; 路由和菜单同步修改
                    validationHints: 验证菜单跳转; 验证路由注册
                    ---
                    - 保留入口文件。
                    - 页面、路由和菜单要一起改。
                    """);

            GenerationSkillLibrary library = new GenerationSkillLibrary(root);
            List<GenerationSkill> matchedSkills = library.match("我要做一个后台管理 dashboard");

            assertEquals(1, matchedSkills.size());
            assertEquals("vue-admin-dashboard", matchedSkills.getFirst().id());
            assertTrue(library.modules(matchedSkills).contains("dashboard"));
            assertTrue(library.contextFileHints(matchedSkills).contains("src/router"));

            List<Map<String, Object>> payloads = library.toPayloads(matchedSkills);
            assertEquals("Vue Admin Dashboard", payloads.getFirst().get("title"));
            assertTrue(String.valueOf(payloads.getFirst().get("promptInstructions")).contains("保留入口文件"));
        } finally {
            try {
                Files.walk(root)
                        .sorted((left, right) -> right.compareTo(left))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                            }
                        });
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void shouldLoadClasspathSkillsFromAgentSkillsDirectory() {
        GenerationSkillLibrary library = new GenerationSkillLibrary(List.of(), true);

        List<GenerationSkill> matchedSkills = library.match("前端官网 landing 页面");

        assertTrue(matchedSkills.stream().anyMatch(skill -> "frontend-design".equals(skill.id())
                || "vue-admin-dashboard".equals(skill.id())
                || "database-boundary".equals(skill.id())
                || "crud-form-flow".equals(skill.id())));
    }
}
