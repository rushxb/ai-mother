package com.rush.rushaicodemother.orchestration.context;

/**
 * 上下文块的语法类别。
 *
 * <p>压缩时按块整体取舍，类别决定被省略时的摘要措辞，以及块自身超预算时
 * 必须保留的收尾语法（例如围栏代码块必须补回闭合围栏）。</p>
 */
public enum ContextBlockKind {

    /** 「当前文件: path」标题 + 围栏代码块构成的文件条目。 */
    FILE_SECTION("已省略文件"),

    /** 独立的围栏代码块。 */
    FENCED_CODE("已省略代码块"),

    /** 由空行分隔的普通文本段落。 */
    PROSE("已省略说明文本");

    private final String omissionLabel;

    ContextBlockKind(String omissionLabel) {
        this.omissionLabel = omissionLabel;
    }

    /**
     * 返回该类别在省略摘要中的中文措辞。
     *
     * @return 省略摘要措辞
     */
    public String omissionLabel() {
        return omissionLabel;
    }
}
