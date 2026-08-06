package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.config.production.ProfileDefaultsEnvironmentPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TemplatePreWarmProfileContractTest {

    @Test
    void developmentProfileShouldEnablePreWarmByDefault() {
        assertEquals(
                "${TEMPLATE_PRE_WARM_ENABLED:true}",
                readTemplatePreWarmEnabledDefault("application-dev.yml")
        );
    }

    @Test
    void productionProfileShouldEnablePreWarmByDefault() {
        // 生产取值已下沉到代码常量，不再由 application-prod.yml 提供。
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("prod");

        new ProfileDefaultsEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertEquals("true", environment.getProperty("app.template-pre-warm.enabled"));
    }

    private Object readTemplatePreWarmEnabledDefault(String resourceName) {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            assertNotNull(inputStream, "缺少配置资源: " + resourceName);
            Map<String, Object> root = new Yaml().load(inputStream);
            Map<String, Object> app = requireMap(root, "app");
            Map<String, Object> templatePreWarm = requireMap(app, "template-pre-warm");
            return templatePreWarm.get("enabled");
        } catch (Exception exception) {
            throw new AssertionError("无法读取配置资源: " + resourceName, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requireMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Map<?, ?>)) {
            throw new AssertionError("配置节点必须是对象: " + key);
        }
        return (Map<String, Object>) value;
    }
}
