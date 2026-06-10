package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

public record CreateTemplateManifest(
        String templateId,
        CodeGenTypeEnum codeGenType,
        String description
) {
}
