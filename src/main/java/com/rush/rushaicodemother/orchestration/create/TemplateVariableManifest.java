package com.rush.rushaicodemother.orchestration.create;

import java.util.List;
import java.util.Map;

public record TemplateVariableManifest(
        String templateId,
        Map<String, Object> variables,
        List<String> supportedModules,
        List<String> renderTargets
) {
}
