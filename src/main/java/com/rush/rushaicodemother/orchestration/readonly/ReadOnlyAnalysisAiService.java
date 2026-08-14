package com.rush.rushaicodemother.orchestration.readonly;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/** LangChain4j 结构化输出接口；不注册任何工具。 */
interface ReadOnlyAnalysisAiService {

    @SystemMessage(fromResource = "prompt/read-only-analysis-system-prompt.txt")
    @UserMessage("""
            操作类型：{{operationType}}

            用户需求：
            {{userPrompt}}

            允许引用的相对路径：
            {{allowedReferences}}

            只读项目上下文：
            {{projectContext}}
            """)
    ReadOnlyAnalysisResult analyze(@V("operationType") String operationType,
                                   @V("userPrompt") String userPrompt,
                                   @V("allowedReferences") String allowedReferences,
                                   @V("projectContext") String projectContext);
}
