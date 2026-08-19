package com.rush.rushaicodemother.orchestration.template.bootstrap;

import java.util.LinkedHashMap;
import java.util.Map;

/** adapter 返回的模板初始化事实，由 registry 补齐工作区身份。 */
public record GenerationTemplateBootstrapOutput(
        boolean successful,
        String artifactName,
        String summary,
        Map<String, Object> templatePayload,
        Map<String, Map<String, Object>> rolePayloads,
        Map<String, Object> contextPayload
) {

    public GenerationTemplateBootstrapOutput {
        artifactName = requireText(artifactName, "模板制品名称不能为空");
        summary = requireText(summary, "模板初始化摘要不能为空");
        templatePayload = immutableMap(templatePayload);
        rolePayloads = immutableNestedMap(rolePayloads);
        contextPayload = immutableMap(contextPayload);
    }

    private static Map<String, Map<String, Object>> immutableNestedMap(
            Map<String, Map<String, Object>> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> copied = new LinkedHashMap<>();
        source.forEach((role, payload) -> copied.put(
                requireText(role, "模板角色不能为空"),
                immutableMap(payload)
        ));
        return Map.copyOf(copied);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        return source == null || source.isEmpty()
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(source));
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
