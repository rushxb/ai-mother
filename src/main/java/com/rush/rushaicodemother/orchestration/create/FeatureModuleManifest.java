package com.rush.rushaicodemother.orchestration.create;

import java.util.List;

public record FeatureModuleManifest(
        String moduleId,
        String name,
        String templateId,
        List<String> slotIds,
        String reason
) {
    public FeatureModuleManifest {
        slotIds = slotIds == null ? List.of() : List.copyOf(slotIds);
    }
}
