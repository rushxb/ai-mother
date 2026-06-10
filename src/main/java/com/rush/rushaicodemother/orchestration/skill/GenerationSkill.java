package com.rush.rushaicodemother.orchestration.skill;

import java.util.List;

/**
 * 生成技能定义。
 */
public record GenerationSkill(
        String id,
        String title,
        String description,
        List<String> keywords,
        List<String> modules,
        List<String> contextFileHints,
        List<String> implementationHints,
        List<String> validationHints,
        boolean databaseRequired,
        String promptInstructions,
        String sourcePath
) {

    public GenerationSkill {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        modules = modules == null ? List.of() : List.copyOf(modules);
        contextFileHints = contextFileHints == null ? List.of() : List.copyOf(contextFileHints);
        implementationHints = implementationHints == null ? List.of() : List.copyOf(implementationHints);
        validationHints = validationHints == null ? List.of() : List.copyOf(validationHints);
        title = title == null ? "" : title.trim();
        description = description == null ? "" : description.trim();
        promptInstructions = promptInstructions == null ? "" : promptInstructions.trim();
        sourcePath = sourcePath == null ? "" : sourcePath.trim();
        id = id == null ? "" : id.trim();
    }
}
