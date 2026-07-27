package com.rush.rushaicodemother.ai.prompt.release;

/** 用于一个不可变提示定义的稳定/金丝雀指针。 */
public record PromptReleaseSpec(
        String stableVersion,
        String canaryVersion,
        int canaryPercentage
) {
    public PromptReleaseSpec {
        stableVersion = normalize(stableVersion);
        canaryVersion = normalize(canaryVersion);
    }

    public boolean hasCanary() {
        return canaryPercentage > 0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
