package com.rush.rushaicodemother.ai.generation;

import com.rush.rushaicodemother.ai.AiCodeGeneratorService;
import com.rush.rushaicodemother.ai.model.MultiFileCodeResult;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.TokenStream;
import org.springframework.stereotype.Component;

/** 原生多文件生成协议适配器。 */
@Component
public class MultiFileLightweightCodeGenerationAdapter
        implements LightweightCodeGenerationAdapter<MultiFileCodeResult> {

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.MULTI_FILE;
    }

    @Override
    public MultiFileCodeResult generate(AiCodeGeneratorService service, String userPrompt) {
        return service.generateMultiFileCode(userPrompt);
    }

    @Override
    public TokenStream generateStream(AiCodeGeneratorService service,
                                      String userPrompt,
                                      InvocationParameters invocationParameters) {
        return service.generateMultiFileCodeStream(userPrompt, invocationParameters);
    }
}
