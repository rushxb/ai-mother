package com.rush.rushaicodemother.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 提示词优化服务
 */
public interface PromptOptimizerService {

    /**
     * 优化用户输入的提示词，用于后续代码生成
     *
     * @param prompt 原始提示词
     * @return 优化后的提示词
     */
    @SystemMessage(fromResource = "prompt/prompt-optimizer-system-prompt.txt")
    String optimizePrompt(@UserMessage String prompt);
}
