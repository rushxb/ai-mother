package com.rush.rushaicodemother.ai.generation;

import com.rush.rushaicodemother.ai.AiCodeGeneratorService;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.TokenStream;

/**
 * 轻量代码生成协议适配器。
 *
 * <p>每个实现封装一种生成类型对应的阻塞与流式 provider 方法，避免调用方感知
 * {@link AiCodeGeneratorService} 上按类型拆分的方法名。</p>
 *
 * @param <T> 阻塞生成结果类型
 */
public interface LightweightCodeGenerationAdapter<T> {

    /** 返回当前适配器唯一负责的生成类型。 */
    CodeGenTypeEnum codeGenType();

    /** 执行阻塞生成。 */
    T generate(AiCodeGeneratorService service, String userPrompt);

    /** 执行流式生成。 */
    TokenStream generateStream(AiCodeGeneratorService service,
                               String userPrompt,
                               InvocationParameters invocationParameters);
}
