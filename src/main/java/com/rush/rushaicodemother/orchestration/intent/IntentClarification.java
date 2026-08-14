package com.rush.rushaicodemother.orchestration.intent;

import dev.langchain4j.model.output.structured.Description;

/**
 * 小模型对模糊意图的结构化澄清结果。
 *
 * <p>刻意只暴露三个维度：这三者是本地关键词最容易判错、且对路由与执行预算影响最大的部分。
 * 破坏性风险与验证等级不在其中——它们涉及安全边界，必须由确定性规则决定，
 * 不接受模型下调。</p>
 *
 * <p>字段使用包装类型：模型未能给出某一维度时保持为 null，由调用方沿用本地结论，
 * 而不是被基本类型默认值悄悄改写。</p>
 */
public class IntentClarification {

    @Description("用户真实意图对应的操作类型：CREATE 新建、EDIT 修改、REPAIR 修复、EXPLAIN 仅解释、AUDIT 只读审计、PLAN 只读方案")
    private IntentOperationType operationType;

    @Description("改动的语义复杂度：LOW 局部微调、MEDIUM 常规功能改动、HIGH 跨模块或架构级改造")
    private IntentSemanticComplexity semanticComplexity;

    @Description("预计需要新增或修改的文件数量，只给出正整数估计值")
    private Integer expectedFileCount;

    public IntentOperationType getOperationType() {
        return operationType;
    }

    public void setOperationType(IntentOperationType operationType) {
        this.operationType = operationType;
    }

    public IntentSemanticComplexity getSemanticComplexity() {
        return semanticComplexity;
    }

    public void setSemanticComplexity(IntentSemanticComplexity semanticComplexity) {
        this.semanticComplexity = semanticComplexity;
    }

    public Integer getExpectedFileCount() {
        return expectedFileCount;
    }

    public void setExpectedFileCount(Integer expectedFileCount) {
        this.expectedFileCount = expectedFileCount;
    }
}
