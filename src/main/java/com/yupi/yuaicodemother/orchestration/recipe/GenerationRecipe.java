package com.yupi.yuaicodemother.orchestration.recipe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成链路内置 recipe，用于把常见需求压缩为稳定的工程指引。
 */
public record GenerationRecipe(
        String id,
        String title,
        String intent,
        List<String> keywords,
        List<String> modules,
        List<String> contextFileHints,
        List<String> implementationSteps,
        List<String> validationHints,
        boolean databaseRequired
) {

    public GenerationRecipe {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        modules = modules == null ? List.of() : List.copyOf(modules);
        contextFileHints = contextFileHints == null ? List.of() : List.copyOf(contextFileHints);
        implementationSteps = implementationSteps == null ? List.of() : List.copyOf(implementationSteps);
        validationHints = validationHints == null ? List.of() : List.copyOf(validationHints);
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("title", title);
        payload.put("intent", intent);
        payload.put("modules", modules);
        payload.put("contextFileHints", contextFileHints);
        payload.put("implementationSteps", implementationSteps);
        payload.put("validationHints", validationHints);
        payload.put("databaseRequired", databaseRequired);
        return payload;
    }
}
