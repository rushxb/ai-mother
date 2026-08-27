package com.rush.rushaicodemother.orchestration.tool;

import java.util.List;
import java.util.Objects;

/** 持久文本结果用于重播已完成的工具调用，而不会重复其副作用。 */
public record ToolExecutionOutcome(
        boolean error,
        String resultText,
        boolean mutationEvidencePresent,
        List<String> effectiveMutationPaths,
        boolean workspaceInvalidated
) {

    private static final int MAX_MUTATION_PATHS = 100;

    public ToolExecutionOutcome {
        resultText = resultText == null ? "" : resultText;
        if (error) {
            // 失败文本永远优先，不允许携带任何工作区成功事实。
            mutationEvidencePresent = false;
            effectiveMutationPaths = List.of();
            workspaceInvalidated = false;
        } else {
            if (workspaceInvalidated && mutationEvidencePresent) {
                throw new IllegalArgumentException(
                        "工作区整体失效事实不能与路径变更证据同时存在");
            }
            if (mutationEvidencePresent) {
                // 只有调用方显式传入空列表，才允许表达“已确认无需变更”。
                effectiveMutationPaths = List.copyOf(Objects.requireNonNull(
                        effectiveMutationPaths,
                        "声明 mutation 证据时必须显式提供路径列表；空列表才表示成功 no-op"));
            } else {
                // 兼容尚未持久化 mutation 证据字段的旧记录。
                effectiveMutationPaths = List.of();
            }
            if (effectiveMutationPaths.size() > MAX_MUTATION_PATHS) {
                throw new IllegalArgumentException(
                        "持久化工具结果中的有效变更路径数量超限");
            }
        }
    }

    /** 兼容既有路径级变更证据调用方。 */
    public ToolExecutionOutcome(boolean error,
                                String resultText,
                                boolean mutationEvidencePresent,
                                List<String> effectiveMutationPaths) {
        this(error, resultText, mutationEvidencePresent, effectiveMutationPaths, false);
    }

    /** 兼容旧调用方和旧持久化记录：只有文本时不声明工作区变更事实。 */
    public ToolExecutionOutcome(boolean error, String resultText) {
        this(error, resultText, false, List.of(), false);
    }
}
