package com.rush.rushaicodemother.orchestration.context;

import java.util.List;
import java.util.Objects;

/**
 * 被折叠的工具循环摘要。
 *
 * <p>窗口溢出时不再静默丢弃最早的消息，而是折叠为本摘要注入系统提示，
 * 使模型仍记得已读过哪些文件、已改过哪些文件、以及尚未解决的构建错误，
 * 避免重复劳动和反复修同一处。</p>
 *
 * @param foldedRounds  被折叠的工具循环轮数
 * @param readPaths     已读取过的文件路径
 * @param mutatedPaths  已写入或修改过的文件路径
 * @param deletedPaths  已删除的文件路径
 * @param confirmedNoMutationCount 已确认目标状态、无需变更的工作区操作数
 * @param unknownMutationCount 成功返回但缺失可信结果证据、需要重新核验的工作区操作数
 * @param unresolvedErrors 尚未解决的失败工具证据（工具名 + 首行错误）
 */
public record AgentToolRoundSummary(
        int foldedRounds,
        List<String> readPaths,
        List<String> mutatedPaths,
        List<String> deletedPaths,
        int confirmedNoMutationCount,
        int unknownMutationCount,
        List<String> unresolvedErrors
) {

    /** 摘要在系统提示中的段落标题，便于模型识别这是平台注入的可信上下文。 */
    private static final String HEADING = "【已折叠的历史工具循环】";

    public AgentToolRoundSummary {
        readPaths = List.copyOf(Objects.requireNonNullElse(readPaths, List.of()));
        mutatedPaths = List.copyOf(Objects.requireNonNullElse(mutatedPaths, List.of()));
        deletedPaths = List.copyOf(Objects.requireNonNullElse(deletedPaths, List.of()));
        confirmedNoMutationCount = Math.max(0, confirmedNoMutationCount);
        unknownMutationCount = Math.max(0, unknownMutationCount);
        unresolvedErrors = List.copyOf(Objects.requireNonNullElse(unresolvedErrors, List.of()));
    }

    /** 空摘要，表示无历史可折叠。 */
    public static AgentToolRoundSummary none() {
        return new AgentToolRoundSummary(
                0, List.of(), List.of(), List.of(), 0, 0, List.of());
    }

    /**
     * 判断摘要是否不含任何值得注入的信息。
     *
     * @return 无内容时返回 {@code true}
     */
    public boolean blank() {
        return foldedRounds <= 0
                && readPaths.isEmpty()
                && mutatedPaths.isEmpty()
                && deletedPaths.isEmpty()
                && confirmedNoMutationCount <= 0
                && unknownMutationCount <= 0
                && unresolvedErrors.isEmpty();
    }

    /**
     * 渲染为注入系统提示的文本段落。
     *
     * @return 摘要文本；空摘要返回空串
     */
    public String render() {
        if (blank()) {
            return "";
        }
        StringBuilder text = new StringBuilder(HEADING);
        text.append("\n为控制上下文预算，最早 ").append(foldedRounds)
                .append(" 轮工具循环的原文已移出对话，事实结论如下。");
        appendPaths(text, "已读取文件", readPaths);
        appendPaths(text, "已写入或修改文件", mutatedPaths);
        appendPaths(text, "已删除文件", deletedPaths);
        if (confirmedNoMutationCount > 0) {
            text.append("\n已确认无需变更的工作区操作：")
                    .append(confirmedNoMutationCount).append(" 次");
        }
        if (unknownMutationCount > 0) {
            text.append("\n结果未知的工作区操作：")
                    .append(unknownMutationCount).append(" 次");
        }
        if (!unresolvedErrors.isEmpty()) {
            text.append("\n未解决的失败证据：");
            unresolvedErrors.forEach(error -> text.append("\n- ").append(error));
        }
        appendRecoveryGuidance(text);
        return text.toString();
    }

    /**
     * 根据摘要中的可验证事实生成恢复指引，避免把失败调用或只读历史误报为已落盘改动。
     */
    private void appendRecoveryGuidance(StringBuilder text) {
        boolean hasLandedWorkspaceChanges = !mutatedPaths.isEmpty() || !deletedPaths.isEmpty();
        if (hasLandedWorkspaceChanges) {
            text.append("\n以上列出的成功改动已落盘，请勿重复执行；");
            if (unknownMutationCount > 0) {
                text.append("另有工作区操作结果未知，请先读取相关目标核验，禁止盲目重放；");
            }
            if (!unresolvedErrors.isEmpty()) {
                text.append("未解决的失败操作尚未落盘，请修正后重试；");
            }
            text.append("需要原文时调用读取工具。");
            return;
        }
        if (unknownMutationCount > 0) {
            text.append("\n结果未知的工作区操作不得视为已落盘或未执行，")
                    .append("请先读取相关目标核验，禁止盲目重放；");
            if (confirmedNoMutationCount > 0) {
                text.append("其余已确认无需变更的操作请勿重复执行；");
            }
            if (!unresolvedErrors.isEmpty()) {
                text.append("失败操作尚未落盘，请修正后重试；");
            }
            text.append("需要原文时调用读取工具。");
            return;
        }
        if (!unresolvedErrors.isEmpty()) {
            if (confirmedNoMutationCount > 0) {
                text.append("\n已确认无需变更的操作请勿重复执行；");
            }
            text.append("\n上述失败操作尚未落盘，请根据失败证据修正后重试；")
                    .append("需要原文时调用读取工具。");
            return;
        }
        if (confirmedNoMutationCount > 0) {
            text.append("\n以上工作区操作已确认目标状态，无需重复执行；")
                    .append("需要原文时调用读取工具。");
            return;
        }
        text.append("\n以上仅为历史读取事实，未确认工作区改动；需要原文时调用读取工具。");
    }

    private void appendPaths(StringBuilder text, String label, List<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        text.append('\n').append(label).append("：").append(String.join("、", paths));
    }
}
