package com.rush.rushaicodemother.orchestration.context;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构感知压缩回归。
 *
 * <p>核心断言：压缩后不得出现被劈成半截的文件块或未闭合围栏 ——
 * 模型读到半份源码会照着残缺内容继续改，这是生成质量最隐蔽的损伤来源。</p>
 */
class StructureAwareContextCompressorTest {

    private final StructureAwareContextCompressor compressor =
            new StructureAwareContextCompressor(new ContextBlockSplitter());

    @Test
    void contentWithinBudgetMustBeReturnedVerbatim() {
        String text = fileSection("src/a.ts", 3);

        assertEquals(text, compressor.compress(text, text.length()));
    }

    @Test
    void compressionMustRetainWholeFileSectionsAndNeverSplitOne() {
        String text = String.join("\n\n",
                fileSection("src/first.ts", 20),
                fileSection("src/middle-one.ts", 20),
                fileSection("src/middle-two.ts", 20),
                fileSection("src/last.ts", 20));

        String compressed = compressor.compress(text, text.length() / 2);

        assertTrue(compressed.length() <= text.length() / 2,
                "压缩结果必须落在预算内，实际 " + compressed.length());
        // 每个保留下来的文件块都必须围栏成对，即语法完整。
        long fences = compressed.lines().filter(line -> line.startsWith("```")).count();
        assertEquals(0, fences % 2, "保留块的围栏必须成对闭合:\n" + compressed);
        assertTrue(compressed.contains("[上下文已按结构压缩"), "必须给出省略提示");
    }

    @Test
    void omittedFilePathsMustBeListedSoModelCanReadThemBack() {
        String text = String.join("\n\n",
                fileSection("src/keep-head.ts", 6),
                fileSection("src/dropped.ts", 200),
                fileSection("src/keep-tail.ts", 6));

        String compressed = compressor.compress(text, 900);

        assertTrue(compressed.contains("src/dropped.ts"),
                "被省略的文件路径必须列出，否则模型无从得知该读哪个文件:\n" + compressed);
        assertFalse(compressed.contains("行 100 of src/dropped.ts"),
                "被省略的文件正文不得保留");
        assertTrue(compressed.contains("调用读取工具"), "必须提示可通过工具补读");
    }

    @Test
    void headMustBePreferredOverTailWhenBudgetIsTight() {
        String text = String.join("\n\n",
                fileSection("src/first.ts", 10),
                fileSection("src/second.ts", 10),
                fileSection("src/third.ts", 10),
                fileSection("src/fourth.ts", 10));

        String compressed = compressor.compress(text, text.length() * 2 / 3);

        assertTrue(compressed.contains("当前文件: src/first.ts"),
                "头部内容优先保留:\n" + compressed);
    }

    @Test
    void singleOversizedBlockMustBeTruncatedAtLineBoundaryWithClosingFence() {
        String text = fileSection("src/huge.ts", 400);

        String compressed = compressor.compress(text, 600);

        assertTrue(compressed.length() <= 600);
        assertTrue(compressed.endsWith("```"), "单块截断必须补回闭合围栏:\n" + compressed);
        assertTrue(compressed.contains("已省略文件 src/huge.ts"), "必须说明发生了截断");
        // 截断点必须落在行边界，不得把某一行切成半截。
        String body = compressed.substring(0, compressed.indexOf("\n[已省略文件"));
        assertTrue(body.lines().skip(2).allMatch(line -> line.endsWith(";")),
                "截断必须落在完整行边界:\n" + body);
    }

    @Test
    void budgetTooSmallForAnyContentMustYieldSummaryOnlyInsteadOfSyntaxFragment() {
        String text = fileSection("src/huge.ts", 400);

        String compressed = compressor.compress(text, 60);

        assertTrue(compressed.length() <= 60);
        assertFalse(compressed.contains("```"),
                "预算过小时不得输出半截围栏碎片: " + compressed);
    }

    @Test
    void blankOrNonPositiveBudgetMustYieldEmptyString() {
        assertEquals("", compressor.compress(null, 100));
        assertEquals("", compressor.compress("", 100));
        assertEquals("", compressor.compress("内容", 0));
    }

    @Test
    void compressionMustAlwaysHonourBudgetAcrossBudgetSweep() {
        String text = String.join("\n\n", IntStream.range(0, 12)
                .mapToObj(index -> fileSection("src/module-" + index + ".ts", 15))
                .toList());

        // 预算扫描：任意预算下都不得越界，防止摘要或闭合围栏把结果顶出预算。
        for (int budget = 40; budget <= text.length(); budget += 37) {
            String compressed = compressor.compress(text, budget);
            assertTrue(compressed.length() <= budget,
                    "预算 " + budget + " 下越界至 " + compressed.length());
        }
    }

    private static String fileSection(String path, int lines) {
        String body = IntStream.range(0, lines)
                .mapToObj(index -> "const value" + index + " = " + index + ";")
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return "当前文件: " + path + "\n```ts\n" + body + "\n```";
    }
}
