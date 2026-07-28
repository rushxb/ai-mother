package com.rush.rushaicodemother.ai.prompt.release;

import java.util.LinkedHashMap;
import java.util.Map;

/** 运行工件可以激活的不可变版本的非敏感清单。 */
public record PromptReleaseCapabilities(
        Map<String, Map<String, String>> contentHashesByPromptAndVersion
) {
    /** 创建提示词发布能力实例并完成必要的依赖和初始状态设置。 */
    public PromptReleaseCapabilities {
        Map<String, Map<String, String>> copied = new LinkedHashMap<>();
        if (contentHashesByPromptAndVersion != null) {
            contentHashesByPromptAndVersion.forEach((key, versions) ->
                    copied.put(key, versions == null ? Map.of() : Map.copyOf(versions)));
        }
        contentHashesByPromptAndVersion = Map.copyOf(copied);
    }

    public static PromptReleaseCapabilities empty() {
        return new PromptReleaseCapabilities(Map.of());
    }

    /**
 * 返回{@code supports}。
 *
 * @param promptKey 提示词键
 * @param version 版本
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean supports(String promptKey, String version) {
        return contentHashesByPromptAndVersion
                .getOrDefault(promptKey, Map.of())
                .containsKey(version);
    }
}
