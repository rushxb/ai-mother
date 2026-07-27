package com.rush.rushaicodemother.ai.prompt;

/** 一种选定的不可变提示版本。 */
public record PromptSelection(
        String promptKey,
        String version,
        Channel channel,
        String content,
        String contentHash,
        String bundleId
) {
    public PromptSelection {
        promptKey = promptKey == null ? "" : promptKey;
        version = version == null ? "" : version;
        channel = channel == null ? Channel.ARCHIVED : channel;
        content = content == null ? "" : content;
        contentHash = contentHash == null ? "" : contentHash;
        bundleId = bundleId == null ? "" : bundleId;
    }

    public enum Channel {
        STABLE,
        CANARY,
        ARCHIVED
    }
}
