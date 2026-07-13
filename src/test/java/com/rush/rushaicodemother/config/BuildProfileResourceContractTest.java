package com.rush.rushaicodemother.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildProfileResourceContractTest {

    @Test
    void packagedApplicationProfileMustMatchMavenBuildProfile() {
        String expectedBuildProfile = System.getProperty("expectedBuildProfile");
        assertTrue(
                "dev".equals(expectedBuildProfile) || "prod".equals(expectedBuildProfile),
                "Maven 构建必须显式解析为 dev 或 prod Profile"
        );

        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(inputStream, "构建产物必须包含 application.yml");

            Map<String, Object> root = new Yaml().load(inputStream);
            Map<String, Object> spring = requireMap(root, "spring");
            Map<String, Object> profiles = requireMap(spring, "profiles");
            Object activeProfile = profiles.get("active");

            assertEquals(
                    expectedBuildProfile,
                    activeProfile,
                    "application.yml 中的活动 Profile 必须与 Maven 构建 Profile 一致"
            );
        } catch (Exception exception) {
            throw new AssertionError("无法读取构建后的 application.yml", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requireMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertTrue(value instanceof Map<?, ?>, "application.yml 缺少对象配置：" + key);
        return (Map<String, Object>) value;
    }
}
