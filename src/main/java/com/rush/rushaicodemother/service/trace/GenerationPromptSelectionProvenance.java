package com.rush.rushaicodemother.service.trace;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 一次模型调用实际使用的不可变 Prompt 版本身份，不包含 Prompt 正文。 */
public record GenerationPromptSelectionProvenance(
        String promptKey,
        String version,
        String channel,
        String contentHash,
        String bundleId
) {

    private static final Pattern KEY_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");
    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> CHANNELS = Set.of("stable", "canary", "archived");
    private static final Comparator<GenerationPromptSelectionProvenance> CANONICAL_ORDER =
            Comparator.comparing(GenerationPromptSelectionProvenance::promptKey)
                    .thenComparing(GenerationPromptSelectionProvenance::version)
                    .thenComparing(GenerationPromptSelectionProvenance::channel)
                    .thenComparing(GenerationPromptSelectionProvenance::contentHash)
                    .thenComparing(GenerationPromptSelectionProvenance::bundleId);

    public GenerationPromptSelectionProvenance {
        promptKey = requirePattern(promptKey, KEY_PATTERN, "Prompt key");
        version = requirePattern(version, VERSION_PATTERN, "Prompt 版本");
        channel = channel == null ? "" : channel.trim().toLowerCase(Locale.ROOT);
        if (!CHANNELS.contains(channel)) {
            throw new IllegalArgumentException("Prompt 发布通道无效");
        }
        contentHash = requirePattern(contentHash, SHA256_PATTERN, "Prompt 内容指纹");
        bundleId = requirePattern(bundleId, SHA256_PATTERN, "Prompt 包指纹");
    }

    /** 为持久化回读和幂等比较建立与消息排列无关的确定性顺序。 */
    public static List<GenerationPromptSelectionProvenance> canonicalize(
            List<GenerationPromptSelectionProvenance> selections
    ) {
        if (selections == null || selections.isEmpty()) {
            return List.of();
        }
        return selections.stream().sorted(CANONICAL_ORDER).toList();
    }

    private static String requirePattern(String value, Pattern pattern, String label) {
        String normalized = value == null ? "" : value.trim();
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(label + "无效");
        }
        return normalized;
    }
}
