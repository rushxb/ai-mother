package com.rush.rushaicodemother.ai.generation;

import com.rush.rushaicodemother.ai.AiCodeGeneratorService;
import com.rush.rushaicodemother.ai.model.HtmlCodeResult;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.TokenStream;
import org.springframework.stereotype.Component;

/** 原生 HTML 生成协议适配器。 */
@Component
public class HtmlLightweightCodeGenerationAdapter
        implements LightweightCodeGenerationAdapter<HtmlCodeResult> {

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.HTML;
    }

    @Override
    public HtmlCodeResult generate(AiCodeGeneratorService service, String userPrompt) {
        return service.generateHtmlCode(userPrompt);
    }

    @Override
    public TokenStream generateStream(AiCodeGeneratorService service,
                                      String userPrompt,
                                      InvocationParameters invocationParameters) {
        return service.generateHtmlCodeStream(userPrompt, invocationParameters);
    }
}
