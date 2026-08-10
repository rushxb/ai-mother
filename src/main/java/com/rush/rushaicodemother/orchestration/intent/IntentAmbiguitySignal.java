package com.rush.rushaicodemother.orchestration.intent;

import java.util.Set;

/**
 * 描述本地关键词解析在哪些维度上"没有真正判断出结果"的结构化歧义信号。
 *
 * <p>置信度只是一个加性评分，无法回答"这个结论是命中关键词得出的，还是走了静默兜底分支"。
 * 而模型兜底恰恰只应该在后者上花钱：本地已经明确命中的意图不需要再问一次模型。
 * 因此该信号只记录"解析来源"这一客观事实，不掺入任何评分，
 * 由 {@link IntentProfileService} 在解析过程中如实填充。</p>
 *
 * <p>信号只保存有限枚举与布尔值，不保存原始提示词，可安全用于路由、观测与持久化。</p>
 *
 * @param unresolvedDimensions 未由关键词命中、只能采用兜底默认值的维度
 * @param scopeFallback true 表示影响范围未命中任何关键词，靠代码生成类型兜底推断
 * @param shortPrompt true 表示提示词过短，本身不足以支撑可靠判断
 */
public record IntentAmbiguitySignal(
        Set<IntentResolutionDimension> unresolvedDimensions,
        boolean scopeFallback,
        boolean shortPrompt
) {

    /** 低于该字符数的提示词无法支撑可靠的本地判断。 */
    public static final int SHORT_PROMPT_THRESHOLD = 6;

    public IntentAmbiguitySignal {
        unresolvedDimensions = unresolvedDimensions == null
                ? Set.of()
                : Set.copyOf(unresolvedDimensions);
    }

    /** 返回"本地解析完全确定"的信号，用于兼容旧任务命令与首次创建等无歧义场景。 */
    public static IntentAmbiguitySignal resolved() {
        return new IntentAmbiguitySignal(Set.of(), false, false);
    }

    /** 返回"完全无法解析"的信号，用于空白提示词等退化输入。 */
    public static IntentAmbiguitySignal unresolved() {
        return new IntentAmbiguitySignal(
                Set.copyOf(Set.of(IntentResolutionDimension.values())), true, true);
    }

    /** 是否存在任何值得进一步澄清的歧义。 */
    public boolean ambiguous() {
        return !unresolvedDimensions.isEmpty() || scopeFallback || shortPrompt;
    }

    /** 指定维度是否只能依赖兜底默认值。 */
    public boolean unresolved(IntentResolutionDimension dimension) {
        return dimension != null && unresolvedDimensions.contains(dimension);
    }
}
