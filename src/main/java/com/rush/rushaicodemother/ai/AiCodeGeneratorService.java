package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.ai.model.HtmlCodeResult;
import com.rush.rushaicodemother.ai.model.MultiFileCodeResult;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/** 仅承载无工具的轻量代码生成；工程项目由显式智能体运行时负责。 */
public interface AiCodeGeneratorService {

    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    @UserMessage("{{userMessage}}")
    HtmlCodeResult generateHtmlCode(@V("userMessage") String userMessage);

    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    @UserMessage("{{userMessage}}")
    MultiFileCodeResult generateMultiFileCode(@V("userMessage") String userMessage);

    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    @UserMessage("{{userMessage}}")
    TokenStream generateHtmlCodeStream(@V("userMessage") String userMessage,
                                       InvocationParameters invocationParameters);

    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    @UserMessage("{{userMessage}}")
    TokenStream generateMultiFileCodeStream(@V("userMessage") String userMessage,
                                            InvocationParameters invocationParameters);
}
