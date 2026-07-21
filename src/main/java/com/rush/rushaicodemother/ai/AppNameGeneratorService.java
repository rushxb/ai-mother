package com.rush.rushaicodemother.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 应用标题生成服务
 */
public interface AppNameGeneratorService {

    /**
     * 根据用户需求生成简短应用标题
     *
     * @param prompt 用户需求
     * @return 应用标题
     */
    @SystemMessage(fromResource = "prompt/app-name-system-prompt.txt")
    String generateAppName(@UserMessage String prompt);
}
