package com.yupi.yuaicodemother.ai;

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
    @SystemMessage(
            "你是一个应用命名助手，负责根据用户的应用需求生成轻量、准确、自然的标题。\n" +
                    "输出要求：\n" +
                    "1. 只输出标题本身，不要解释、不要前后缀、不要换行。\n" +
                    "2. 标题应尽量简短，控制在 4 到 12 个汉字或等价短语内，总长度不超过 16 个字符。\n" +
                    "3. 要准确概括核心用途和场景，避免空泛词汇，比如“智能平台”“系统应用”。\n" +
                    "4. 不要使用引号、书名号、句号、冒号等包裹性标点。\n" +
                    "5. 默认输出中文；如果用户需求明显是英文场景，可以输出简短英文标题。"
    )
    String generateAppName(@UserMessage String prompt);
}
