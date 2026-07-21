package com.rush.rushaicodemother.ai.prompt.release;

/** Stable/canary pointers for one immutable prompt definition. */
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
