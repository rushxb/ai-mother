package com.rush.rushaicodemother.orchestration.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把上下文文本切分为可整体取舍的语法块。
 *
 * <p>识别三类边界，优先级由高到低：
 * <ol>
 *   <li>文件条目：{@code 当前文件: path} 标题紧跟围栏代码块，由
 *       {@link GeneratedProjectContextService#buildSelectedFileSections} 产出；</li>
 *   <li>独立围栏代码块：``` 或 ~~~ 开启，同字符同长度或更长的围栏闭合；</li>
 *   <li>普通段落：由空行分隔的文本。</li>
 * </ol>
 *
 * <p>只负责切分，不做任何预算决策，预算由 {@link StructureAwareContextCompressor} 承担。</p>
 */
@Component
public class ContextBlockSplitter {

    /** 文件条目标题，与 {@code GeneratedProjectContextService} 的输出格式保持一致。 */
    private static final Pattern FILE_SECTION_HEADER = Pattern.compile("^当前文件: (\\S.*)$");

    /** 围栏起始行：至少三个反引号或波浪号，后可跟语言标识。 */
    private static final Pattern FENCE_OPEN = Pattern.compile("^([`~]{3,})\\s*(\\S*)\\s*$");

    /**
     * 将文本切分为语法块序列。
     *
     * @param text 待切分文本，允许为空
     * @return 按原序排列的语法块；文本为空时返回空列表
     */
    public List<ContextBlock> split(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> lines = List.of(text.split("\n", -1));
        List<ContextBlock> blocks = new ArrayList<>();
        int index = 0;
        // 按既定顺序逐行推进，每次识别出一个完整块后跳到该块之后继续。
        while (index < lines.size()) {
            if (lines.get(index).isBlank()) {
                index++;
                continue;
            }
            int consumed = readFileSection(lines, index, blocks);
            if (consumed == 0) {
                consumed = readFencedCode(lines, index, blocks);
            }
            if (consumed == 0) {
                consumed = readProse(lines, index, blocks);
            }
            index += consumed;
        }
        return List.copyOf(blocks);
    }

    /**
     * 尝试从 {@code start} 处读取一个「标题 + 围栏」文件条目。
     *
     * @return 消费的行数；不匹配时返回 0
     */
    private int readFileSection(List<String> lines, int start, List<ContextBlock> blocks) {
        Matcher header = FILE_SECTION_HEADER.matcher(lines.get(start));
        if (!header.matches() || start + 1 >= lines.size()) {
            return 0;
        }
        Matcher fence = FENCE_OPEN.matcher(lines.get(start + 1));
        if (!fence.matches()) {
            return 0;
        }
        int fenceEnd = findFenceEnd(lines, start + 1, fence.group(1));
        blocks.add(new ContextBlock(
                ContextBlockKind.FILE_SECTION,
                header.group(1).trim(),
                join(lines, start, fenceEnd),
                fence.group(1)
        ));
        return fenceEnd - start + 1;
    }

    /**
     * 尝试从 {@code start} 处读取一个独立围栏代码块。
     *
     * @return 消费的行数；不匹配时返回 0
     */
    private int readFencedCode(List<String> lines, int start, List<ContextBlock> blocks) {
        Matcher fence = FENCE_OPEN.matcher(lines.get(start));
        if (!fence.matches()) {
            return 0;
        }
        int fenceEnd = findFenceEnd(lines, start, fence.group(1));
        blocks.add(new ContextBlock(
                ContextBlockKind.FENCED_CODE,
                fence.group(2),
                join(lines, start, fenceEnd),
                fence.group(1)
        ));
        return fenceEnd - start + 1;
    }

    /**
     * 读取到下一个空行或下一个块起始行为止的普通段落。
     *
     * @return 消费的行数，至少为 1
     */
    private int readProse(List<String> lines, int start, List<ContextBlock> blocks) {
        int end = start;
        // 段落在空行处结束；同时让位于随后出现的文件条目或围栏块。
        while (end + 1 < lines.size()
                && !lines.get(end + 1).isBlank()
                && !startsNewBlock(lines, end + 1)) {
            end++;
        }
        blocks.add(new ContextBlock(
                ContextBlockKind.PROSE, "", join(lines, start, end), ""));
        return end - start + 1;
    }

    /** 判断该行是否开启一个新的文件条目或围栏块。 */
    private boolean startsNewBlock(List<String> lines, int index) {
        return FILE_SECTION_HEADER.matcher(lines.get(index)).matches()
                || FENCE_OPEN.matcher(lines.get(index)).matches();
    }

    /**
     * 定位围栏的闭合行。
     *
     * <p>未闭合时退化为文本末尾，压缩器会在需要时补回闭合围栏，因此不抛异常 ——
     * 上游内容可能本身就已被字符预算截断过。</p>
     *
     * @return 闭合行下标，或文本最后一行的下标
     */
    private int findFenceEnd(List<String> lines, int openIndex, String openFence) {
        for (int index = openIndex + 1; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            if (line.length() >= openFence.length()
                    && line.chars().allMatch(character -> character == openFence.charAt(0))) {
                return index;
            }
        }
        return lines.size() - 1;
    }

    private String join(List<String> lines, int start, int end) {
        return String.join("\n", lines.subList(start, end + 1)).stripTrailing();
    }
}
