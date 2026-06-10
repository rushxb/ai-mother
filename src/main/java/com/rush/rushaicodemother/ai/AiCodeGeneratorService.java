package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.ai.model.HtmlCodeResult;
import com.rush.rushaicodemother.ai.model.MultiFileCodeResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import reactor.core.publisher.Flux;

public interface AiCodeGeneratorService {

    /**
     * 生成 HTML 代码
     *
     * @param userMessage 用户提示词
     * @return AI 的输出结果
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    @UserMessage("{{userMessage}}")
    HtmlCodeResult generateHtmlCode(@V("userMessage") String userMessage);

    /**
     * 生成多文件代码
     *
     * @param userMessage 用户提示词
     * @return AI 的输出结果
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    @UserMessage("{{userMessage}}")
    MultiFileCodeResult generateMultiFileCode(@V("userMessage") String userMessage);

    /**
     * 生成 HTML 代码
     *
     * @param userMessage 用户提示词
     * @return AI 的输出结果
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    @UserMessage("{{userMessage}}")
    Flux<String> generateHtmlCodeStream(@V("userMessage") String userMessage);

    /**
     * 生成多文件代码
     *
     * @param userMessage 用户提示词
     * @return AI 的输出结果
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    @UserMessage("{{userMessage}}")
    Flux<String> generateMultiFileCodeStream(@V("userMessage") String userMessage);

    /**
     * 生成 Vue 项目代码（流式）
     *
     * @param userMessage 用户提示词
     * @return AI 的输出结果
     */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    @UserMessage("{{userMessage}}")
    TokenStream generateVueProjectCodeStream(@MemoryId long appId, @V("userMessage") String userMessage);

    /**
     * 生成后端项目代码（流式）
     *
     * @param userMessage 用户提示词
     * @return AI 的输出结果
     */
    @SystemMessage(fromResource = "prompt/codegen-backend-project-system-prompt.txt")
    @UserMessage("{{userMessage}}")
    TokenStream generateBackendProjectCodeStream(@MemoryId long appId, @V("userMessage") String userMessage);

    /**
     * 生成全栈项目代码（流式）
     *
     * @param userMessage 用户提示词
     * @return AI 的输出结果
     */
    @SystemMessage(fromResource = "prompt/codegen-full-stack-project-system-prompt.txt")
    @UserMessage("{{userMessage}}")
    TokenStream generateFullStackProjectCodeStream(@MemoryId long appId, @V("userMessage") String userMessage);
}
