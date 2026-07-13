package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * AI service for compact CREATE variable specs.
 */
public interface AiCreateSpecService {

    @SystemMessage(fromResource = "prompt/create-spec-system-prompt.txt")
    @UserMessage("用户需求：{{userMessage}}\n\n代码生成类型：{{codeGenType}}\n\n基础模板：{{baseTemplateId}}\n\n计划模块：{{plannedModules}}")
    CreateSpec generateSpec(@V("userMessage") String userMessage,
                            @V("codeGenType") String codeGenType,
                            @V("baseTemplateId") String baseTemplateId,
                            @V("plannedModules") String plannedModules);
}
