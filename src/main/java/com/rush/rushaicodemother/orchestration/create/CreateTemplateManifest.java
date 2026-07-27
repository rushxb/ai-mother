package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

/**
 * 创建模板清单的不可变数据载体。
 */
public record CreateTemplateManifest(
        String templateId,
        CodeGenTypeEnum codeGenType,
        String description
) {
}
