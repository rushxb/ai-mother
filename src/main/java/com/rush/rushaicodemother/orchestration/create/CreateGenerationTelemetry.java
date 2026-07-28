package com.rush.rushaicodemother.orchestration.create;

import java.util.List;
import java.util.Map;

/**
 * 创建生成遥测的不可变数据载体。
 */
public record CreateGenerationTelemetry(
        String baseTemplate,
        List<String> modules,
        int slotGroupCount,
        int aiCallCount,
        int patchCount,
        long validationDurationMs,
        boolean fallback,
        String fallbackReason,
        boolean degraded,
        List<String> degradeReasons
) {
    public CreateGenerationTelemetry {
        modules = modules == null ? List.of() : List.copyOf(modules);
        fallbackReason = fallbackReason == null ? "" : fallbackReason;
        degradeReasons = degradeReasons == null ? List.of() : List.copyOf(degradeReasons);
    }

    /**
 * 将当前对象转换为载荷。
 *
 * @return 载荷集合
 */
    public Map<String, Object> toPayload() {
        return Map.of(
                "baseTemplate", baseTemplate == null ? "" : baseTemplate,
                "modules", modules,
                "slotGroupCount", slotGroupCount,
                "aiCallCount", aiCallCount,
                "patchCount", patchCount,
                "validationDurationMs", validationDurationMs,
                "fallback", fallback,
                "fallbackReason", fallbackReason,
                "degraded", degraded,
                "degradeReasons", degradeReasons
        );
    }
}
