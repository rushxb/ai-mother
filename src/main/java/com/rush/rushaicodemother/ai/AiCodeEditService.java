package com.rush.rushaicodemother.ai;

import com.rush.rushaicodemother.ai.model.EditResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * AI 代码编辑服务接口。
 * 模型只返回结构化 edit operations，不返回完整项目代码。
 */
public interface AiCodeEditService {

    /**
     * 根据用户需求和项目上下文生成编辑操作。
     *
     * @param userMessage   用户需求
     * @param projectContext 项目上下文（定位到的文件内容）
     * @return 编辑结果
     */
    @SystemMessage(fromResource = "prompt/code-edit-system-prompt.txt")
    @UserMessage("用户需求：{{userMessage}}\n\n项目上下文：{{projectContext}}")
    EditResult editCode(@V("userMessage") String userMessage, @V("projectContext") String projectContext);
}
