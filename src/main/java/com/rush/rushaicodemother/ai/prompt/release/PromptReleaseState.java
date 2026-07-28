package com.rush.rushaicodemother.ai.prompt.release;

import java.util.LinkedHashMap;
import java.util.Map;

/** 每个应用程序节点加载的原子数据库发布包。 */
public record PromptReleaseState(
        long revision,
        Map<String, PromptReleaseRecord> releases
) {
    /** 创建提示词发布状态实例并完成必要的依赖和初始状态设置。 */
    public PromptReleaseState {
        if (revision < 0) {
            throw new IllegalArgumentException("prompt release revision cannot be negative");
        }
        releases = releases == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(releases));
    }

    public static PromptReleaseState empty() {
        return new PromptReleaseState(0L, Map.of());
    }
}
