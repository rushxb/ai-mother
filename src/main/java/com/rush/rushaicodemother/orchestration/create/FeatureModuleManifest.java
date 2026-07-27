package com.rush.rushaicodemother.orchestration.create;

import java.util.List;

/**
 * 功能模块清单的不可变数据载体。
 */
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
