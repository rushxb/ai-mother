package com.rush.rushaicodemother.orchestration.context;

import java.util.Objects;

/**
 * 结构感知压缩的最小取舍单元。
 *
 * <p>一个块要么整体保留、要么整体省略，绝不在中间切断，避免把 JSON、围栏代码块或
 * import 语句截成语法碎片后再送入模型。</p>
 *
 * @param kind         语法类别
 * @param label        用于省略摘要的标识（文件条目为相对路径，其余为语言或空串）
 * @param content      块的完整原文，不含块之间的分隔空行
 * @param closingFence 块自身超预算被截断时必须补回的闭合语法；无则为空串
 */
public record ContextBlock(
        ContextBlockKind kind,
        String label,
        String content,
        String closingFence
) {

    public ContextBlock {
        Objects.requireNonNull(kind, "上下文块类别不能为空");
        Objects.requireNonNull(content, "上下文块内容不能为空");
        label = label == null ? "" : label;
        closingFence = closingFence == null ? "" : closingFence;
    }

    /**
     * 统计块的行数，用于生成「省略了多少内容」的摘要。
     *
     * @return 块的行数
     */
    public int lineCount() {
        if (content.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /**
     * 渲染该块在被省略时保留的一行摘要，使模型仍知道此处存在过什么内容。
     *
     * @return 一行省略摘要
     */
    public String omissionSummary() {
        String suffix = label.isBlank() ? "" : " " + label;
        return "[" + kind.omissionLabel() + suffix + "，" + lineCount() + " 行]";
    }
}
