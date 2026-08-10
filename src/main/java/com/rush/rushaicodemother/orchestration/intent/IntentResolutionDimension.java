package com.rush.rushaicodemother.orchestration.intent;

/**
 * 本地意图解析中可能"判不出结果"的维度。
 *
 * <p>只列出确实存在静默兜底分支的维度：这些分支返回的是保守默认值而非真实结论，
 * 是模型兜底唯一值得介入的地方。</p>
 */
public enum IntentResolutionDimension {

    /** 操作类型未命中修复/解释等关键词，落到默认的编辑语义。 */
    OPERATION_TYPE("操作类型"),

    /** 影响范围未命中任何领域关键词。 */
    AFFECTED_SCOPE("影响范围"),

    /** 复杂度未命中高复杂度或轻量编辑特征，落到默认的中等档。 */
    SEMANTIC_COMPLEXITY("语义复杂度"),

    /** 改动文件数只能按范围数量粗略推断。 */
    EXPECTED_FILE_COUNT("预期改动文件数");

    private final String description;

    IntentResolutionDimension(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
