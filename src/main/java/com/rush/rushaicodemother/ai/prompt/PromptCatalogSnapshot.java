package com.rush.rushaicodemother.ai.prompt;

import java.util.Map;

/** 附加到基准和模型来源的不可变发行包。 */
public record PromptCatalogSnapshot(
        String bundleId,
        Map<String, PromptRelease> releases
) {
    public PromptCatalogSnapshot {
        bundleId = bundleId == null ? "" : bundleId;
        releases = releases == null ? Map.of() : Map.copyOf(releases);
    }

    public static PromptCatalogSnapshot unmanaged() {
        return new PromptCatalogSnapshot("", Map.of());
    }

    /**
 * 返回{@code managed}。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean managed() {
        return !bundleId.isBlank() && !releases.isEmpty();
    }

    public record PromptRelease(
            String stableVersion,
            String stableContentHash,
            String canaryVersion,
            String canaryContentHash,
            int canaryPercentage
    ) {
        public PromptRelease {
            stableVersion = stableVersion == null ? "" : stableVersion;
            stableContentHash = stableContentHash == null ? "" : stableContentHash;
            canaryVersion = canaryVersion == null ? "" : canaryVersion;
            canaryContentHash = canaryContentHash == null ? "" : canaryContentHash;
            canaryPercentage = Math.max(0, Math.min(100, canaryPercentage));
        }
    }
}
