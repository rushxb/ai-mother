package com.rush.rushaicodemother.orchestration.create;

import java.util.List;
import java.util.Map;

/**
 * 模板变量清单的不可变数据载体。
 */
public record TemplateVariableManifest(
        String templateId,
        Map<String, Object> variables,
        List<String> supportedModules,
        List<String> renderTargets
) {
}
