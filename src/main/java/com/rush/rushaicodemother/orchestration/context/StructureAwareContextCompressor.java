package com.rush.rushaicodemother.orchestration.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 结构感知的上下文压缩器。
 *
 * <p>与按字符位置掐头留尾不同，本压缩器以 {@link ContextBlock} 为最小取舍单元：
 * 宁可整块丢弃也不把语法单元切碎，被丢弃的块保留一行摘要（标识 + 行数），
 * 使模型知道此处存在过什么、可以主动调用读取工具补齐。</p>
 *
 * <p>保留策略为「头尾优先、中间省略」：头部承载任务与规范，尾部承载最新证据，
 * 二者对生成质量的边际价值最高。</p>
 */
@Component
public class StructureAwareContextCompressor {

    /** 头部预算占比；其余留给尾部与省略摘要。 */
    private static final int HEAD_BUDGET_NUMERATOR = 2;
    private static final int HEAD_BUDGET_DENOMINATOR = 3;

    /** 单块超预算时，块尾部至少保留的字符数，保证闭合语法有落脚处。 */
    private static final int MIN_BLOCK_CHARS = 80;

    /** 省略摘要总述行的预留字符数。 */
    private static final int OMISSION_HEADING_RESERVE = 96;

    /** 每条省略路径的预留字符数。 */
    private static final int OMISSION_ENTRY_RESERVE = 48;

    /** 省略摘要中最多逐条列出的路径数，超出部分并入尾行统计。 */
    private static final int MAX_LISTED_OMITTED_PATHS = 20;

    /**
     * 省略摘要最多可占用的预算比例（分母）。
     *
     * <p>摘要预留过多会导致「预算够放一块源码，却因预留而一块都放不下」，
     * 因此必须设上限；渲染端已按传入预算硬裁剪，预留偏小不会造成越界。</p>
     */
    private static final int OMISSION_BUDGET_DIVISOR = 4;

    /** 摘要尾行统计所需的预留字符数。 */
    private static final int OMISSION_TALLY_RESERVE = 40;

    private final ContextBlockSplitter splitter;

    public StructureAwareContextCompressor(ContextBlockSplitter splitter) {
        this.splitter = Objects.requireNonNull(splitter, "上下文块切分器不能为空");
    }

    /**
     * 将文本压缩到字符预算内，保持语法块完整。
     *
     * @param text     待压缩文本
     * @param maxChars 字符预算，必须为正
     * @return 压缩结果；输入为空时返回空串
     */
    public String compress(String text, int maxChars) {
        if (text == null || text.isEmpty() || maxChars <= 0) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        List<ContextBlock> blocks = splitter.split(text);
        if (blocks.isEmpty()) {
            return "";
        }
        if (blocks.size() == 1) {
            return truncateSingleBlock(blocks.getFirst(), maxChars);
        }
        return selectHeadAndTail(blocks, maxChars);
    }

    /**
     * 双向贪心选择：先从头部吃到头预算用尽，再从尾部回填剩余预算。
     *
     * <p>预留省略摘要所需空间，避免出现「摘要本身放不下」的退化结果。</p>
     */
    private String selectHeadAndTail(List<ContextBlock> blocks, int maxChars) {
        // 摘要空间必须先从总预算中扣除：它一定会出现，且与保留头尾无关。
        // 只从尾部预算扣除是不够的 —— 尾部为空时摘要会把结果重新顶出预算。
        int contentBudget = maxChars - omissionBudget(blocks, maxChars) - separatorCost(false);
        if (contentBudget < MIN_BLOCK_CHARS) {
            // 预算连一块内容加摘要都装不下，退化为单块截断。
            return truncateSingleBlock(blocks.getFirst(), maxChars);
        }
        int headBudget = Math.max(1,
                contentBudget * HEAD_BUDGET_NUMERATOR / HEAD_BUDGET_DENOMINATOR);
        List<ContextBlock> head = new ArrayList<>();
        int headChars = 0;
        int headIndex = 0;
        while (headIndex < blocks.size()) {
            ContextBlock block = blocks.get(headIndex);
            int cost = block.content().length() + separatorCost(head.isEmpty());
            if (headChars + cost > headBudget) {
                break;
            }
            head.add(block);
            headChars += cost;
            headIndex++;
        }

        List<ContextBlock> tail = new ArrayList<>();
        int tailChars = 0;
        int tailIndex = blocks.size() - 1;
        int tailBudget = contentBudget - headChars;
        while (tailIndex >= headIndex) {
            ContextBlock block = blocks.get(tailIndex);
            int cost = block.content().length() + separatorCost(false);
            if (tailChars + cost > tailBudget) {
                break;
            }
            tail.addFirst(block);
            tailChars += cost;
            tailIndex--;
        }

        List<ContextBlock> omitted = blocks.subList(headIndex, tailIndex + 1);
        if (head.isEmpty() && tail.isEmpty()) {
            // 首块即超预算：退化为单块截断，保证至少交付最相关的内容。
            return truncateSingleBlock(blocks.getFirst(), maxChars);
        }
        String rendered = render(head, omitted, tail, maxChars - headChars - tailChars
                - separatorCost(false));
        // 兜底：摘要估算若仍偏小，退化为单块截断而不是交付越界结果。
        return rendered.length() <= maxChars
                ? rendered
                : truncateSingleBlock(blocks.getFirst(), maxChars);
    }

    /**
     * 单个块超出整体预算时的兜底：按行截断并补回闭合语法。
     *
     * <p>此时无块可丢弃，只能在块内部截断，但仍保证以完整行结束、围栏正确闭合，
     * 不会把最后一行切成半截语法。</p>
     */
    private String truncateSingleBlock(ContextBlock block, int maxChars) {
        String closing = block.closingFence().isEmpty()
                ? ""
                : "\n" + block.closingFence();
        String marker = "\n" + block.omissionSummary();
        int contentBudget = maxChars - closing.length() - marker.length();
        if (contentBudget < MIN_BLOCK_CHARS) {
            // 预算过小，连摘要都放不下时只回摘要，避免输出无意义的语法碎片。
            String summary = block.omissionSummary();
            return summary.length() <= maxChars ? summary : "";
        }
        String truncated = truncateAtLineBoundary(block.content(), contentBudget);
        return truncated + marker + closing;
    }

    /** 在不超过预算的前提下截到最后一个完整行边界。 */
    private String truncateAtLineBoundary(String content, int maxChars) {
        if (content.length() <= maxChars) {
            return content;
        }
        int lineBreak = content.lastIndexOf('\n', maxChars);
        return lineBreak <= 0
                ? content.substring(0, maxChars)
                : content.substring(0, lineBreak);
    }

    /** 渲染保留块与省略摘要，块之间以空行分隔。 */
    private String render(List<ContextBlock> head,
                          List<ContextBlock> omitted,
                          List<ContextBlock> tail,
                          int summaryBudget) {
        List<String> parts = new ArrayList<>(head.size() + tail.size() + 1);
        head.forEach(block -> parts.add(block.content()));
        if (!omitted.isEmpty()) {
            parts.add(renderOmissionSummary(omitted, summaryBudget));
        }
        tail.forEach(block -> parts.add(block.content()));
        return String.join("\n\n", parts);
    }

    /**
     * 汇总被省略的块。
     *
     * <p>文件条目逐条列出路径与行数，供模型判断是否需要主动读取；
     * 其余类别只汇总数量，避免摘要本身抢占预算。</p>
     *
     * <p>路径清单受 {@code budget} 硬约束：装不下的条目并入尾行统计，
     * 因此摘要长度可证明不超预算，不依赖估算是否准确。</p>
     */
    private String renderOmissionSummary(List<ContextBlock> omitted, int budget) {
        String heading = "[上下文已按结构压缩，省略 " + omitted.size()
                + " 个内容块，如需查看请调用读取工具]";
        List<String> pathLines = new ArrayList<>();
        int used = heading.length();
        int foldedBlocks = 0;
        int foldedLines = 0;
        for (ContextBlock block : omitted) {
            String entry = block.kind() == ContextBlockKind.FILE_SECTION
                    ? "\n- " + block.label() + "（" + block.lineCount() + " 行）"
                    : null;
            // 非文件块与超出预算的文件条目一并折进尾行统计。
            if (entry == null || used + entry.length() + OMISSION_TALLY_RESERVE > budget) {
                foldedBlocks++;
                foldedLines += block.lineCount();
                continue;
            }
            pathLines.add(entry);
            used += entry.length();
        }
        StringBuilder summary = new StringBuilder(heading);
        pathLines.forEach(summary::append);
        if (foldedBlocks > 0) {
            summary.append("\n- 另有 ").append(foldedBlocks)
                    .append(" 个内容块，共 ").append(foldedLines).append(" 行");
        }
        return summary.toString();
    }

    /** 估算省略摘要所需字符数：一行总述 + 每个文件条目一行，上限受总预算约束。 */
    private int omissionBudget(List<ContextBlock> blocks, int maxChars) {
        int fileSections = (int) blocks.stream()
                .filter(block -> block.kind() == ContextBlockKind.FILE_SECTION)
                .count();
        int estimated = OMISSION_HEADING_RESERVE
                + Math.min(fileSections, MAX_LISTED_OMITTED_PATHS) * OMISSION_ENTRY_RESERVE;
        // 摘要不得吃掉过多预算，否则内容块会全被挤出，压缩退化为纯截断。
        return Math.min(estimated,
                Math.max(OMISSION_HEADING_RESERVE, maxChars / OMISSION_BUDGET_DIVISOR));
    }

    private int separatorCost(boolean first) {
        return first ? 0 : 2;
    }
}
