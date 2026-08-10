package com.rush.rushaicodemother.orchestration.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文块切分回归。
 *
 * <p>切分必须与 {@link GeneratedProjectContextService} 实际产出的格式严格对齐，
 * 否则压缩会退化为按字符截断，把源码从中间劈开。</p>
 */
class ContextBlockSplitterTest {

    private final ContextBlockSplitter splitter = new ContextBlockSplitter();

    @Test
    void fileSectionMustBeSplitAsWholeBlockWithPathLabel() {
        String text = """
                当前文件: src/main.ts
                ```ts
                const app = createApp(App);
                app.mount('#app');
                ```

                当前文件: src/App.vue
                ```vue
                <template><div /></template>
                ```""";

        List<ContextBlock> blocks = splitter.split(text);

        assertEquals(2, blocks.size());
        assertEquals(ContextBlockKind.FILE_SECTION, blocks.getFirst().kind());
        assertEquals("src/main.ts", blocks.getFirst().label());
        assertTrue(blocks.getFirst().content().contains("app.mount('#app');"));
        assertEquals("src/App.vue", blocks.getLast().label());
    }

    @Test
    void nestedFenceMustNotTerminateOuterFileSection() {
        // 生产端 selectFence 会为含反引号的内容升级围栏长度，切分必须按长度匹配收尾。
        String text = """
                当前文件: docs/readme.md
                ````md
                示例：
                ```ts
                const answer = 42;
                ```
                ````""";

        List<ContextBlock> blocks = splitter.split(text);

        assertEquals(1, blocks.size());
        assertEquals("docs/readme.md", blocks.getFirst().label());
        assertTrue(blocks.getFirst().content().contains("const answer = 42;"),
                "内层围栏不得提前结束外层文件块");
    }

    @Test
    void unclosedFenceMustDegradeToEndOfTextInsteadOfThrowing() {
        // 上游可能已按字符预算截断过，未闭合围栏是正常输入而非异常。
        String text = """
                当前文件: src/broken.ts
                ```ts
                const partial = 1;""";

        List<ContextBlock> blocks = splitter.split(text);

        assertEquals(1, blocks.size());
        assertTrue(blocks.getFirst().content().contains("const partial = 1;"));
    }

    @Test
    void proseMustBeSplitAtBlankLineBoundaries() {
        String text = "第一段说明\n继续说明\n\n第二段说明";

        List<ContextBlock> blocks = splitter.split(text);

        assertEquals(2, blocks.size());
        assertTrue(blocks.stream().allMatch(block -> block.kind() == ContextBlockKind.PROSE));
        assertEquals("第一段说明\n继续说明", blocks.getFirst().content());
        assertEquals("第二段说明", blocks.getLast().content());
    }

    @Test
    void bareFencedCodeWithoutFileHeaderMustBeItsOwnBlock() {
        String text = "说明文字\n\n```json\n{\"a\":1}\n```";

        List<ContextBlock> blocks = splitter.split(text);

        assertEquals(2, blocks.size());
        assertEquals(ContextBlockKind.PROSE, blocks.getFirst().kind());
        assertEquals(ContextBlockKind.FENCED_CODE, blocks.getLast().kind());
    }

    @Test
    void emptyInputMustYieldNoBlocks() {
        assertTrue(splitter.split(null).isEmpty());
        assertTrue(splitter.split("").isEmpty());
        assertTrue(splitter.split("\n\n  \n").isEmpty());
    }

    @Test
    void splitMustLoseNoNonBlankContent() {
        String text = """
                项目上下文如下：

                当前文件: src/store.ts
                ```ts
                export const useStore = () => 1;
                ```

                当前文件: src/api.ts
                ```ts
                export const fetchUser = () => null;
                ```""";

        List<ContextBlock> blocks = splitter.split(text);

        String rejoined = String.join("\n", blocks.stream().map(ContextBlock::content).toList());
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                assertTrue(rejoined.contains(line), "切分不得丢弃内容行: " + line);
            }
        }
        assertFalse(blocks.isEmpty());
    }
}
