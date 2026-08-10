package com.rush.rushaicodemother.orchestration.intent;

/**
 * 判定一次意图解析是否值得额外花费一次模型调用来澄清。
 *
 * <p>门禁存在的唯一理由是成本：本地解析已经明确命中关键词时再问一次模型没有收益，
 * 而"几乎每条提示词都有一点歧义"时无差别触发又会把路由阶段变成固定开销。
 * 因此这里同时设下三道限制：</p>
 *
 * <ul>
 *   <li>首次生成不澄清——路由由空工作区这一客观事实决定，模型无从改进；</li>
 *   <li>未解析维度必须达到阈值——只有一个维度靠兜底时，误判代价低于调用代价；</li>
 *   <li>提示词过短时直接放行——语义信息不足，模型同样只能猜测。</li>
 * </ul>
 *
 * <p>阈值以常量下沉在此处而非 yaml：它是内部成本策略，不需要按环境改写，
 * 变更需要随代码评审与发布指纹一起收敛。</p>
 */
public final class IntentClarificationPolicy {

    /**
     * 触发澄清所需的最少未解析维度数。
     *
     * <p>取 2 的依据是实测：单一维度靠兜底（例如仅复杂度未定）时本地结论通常仍可用；
     * 而两个及以上维度同时兜底的提示词，实测普遍伴随范围误判或规模低估。</p>
     */
    public static final int MINIMUM_UNRESOLVED_DIMENSIONS = 2;

    private IntentClarificationPolicy() {
    }

    /**
     * 判断给定意图画像是否需要模型澄清。
     *
     * @param profile 本地解析得到的意图画像
     * @return true 表示值得为澄清意图付出一次模型调用
     */
    public static boolean requiresClarification(IntentProfile profile) {
        if (profile == null) {
            return false;
        }
        IntentAmbiguitySignal signal = profile.ambiguitySignal();
        if (signal == null || !signal.ambiguous()) {
            return false;
        }
        // 提示词本身信息不足，模型澄清同样是猜测，不值得付费。
        if (signal.shortPrompt()) {
            return false;
        }
        // 首次生成的路由由工作区状态决定，澄清无法改变结果。
        if (profile.operationType() == IntentOperationType.CREATE) {
            return false;
        }
        return signal.unresolvedDimensions().size() >= MINIMUM_UNRESOLVED_DIMENSIONS;
    }
}
