package com.rush.rushaicodemother.orchestration.intent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 使用小模型澄清本地关键词无法判定的意图维度。
 *
 * <p>返回结构化对象而非自由文本，避免在业务侧解析模型输出。</p>
 */
public interface IntentClarificationService {

    /**
     * 澄清一次模糊意图。
     *
     * @param userPrompt 用户原始需求描述
     * @param unresolvedDimensions 本地解析未能判定的维度说明
     * @return 结构化澄清结果
     */
    @SystemMessage(fromResource = "prompt/intent-clarification-system-prompt.txt")
    @UserMessage("待澄清的需求：{{userPrompt}}\n\n本地解析无法判定的维度：{{unresolvedDimensions}}")
    IntentClarification clarify(@V("userPrompt") String userPrompt,
                                @V("unresolvedDimensions") String unresolvedDimensions);
}
