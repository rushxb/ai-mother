package com.rush.rushaicodemother.ai.generation;

import com.rush.rushaicodemother.ai.AiCodeGeneratorService;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.TokenStream;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 轻量代码生成统一入口。
 *
 * <p>注册表在启动阶段校验协议唯一性；新增轻量生成类型只需注册新的 adapter，
 * 门面和重试/取消治理流程无需修改。</p>
 */
@Component
public class LightweightCodeGenerationExecutor {

    private final Map<CodeGenTypeEnum, LightweightCodeGenerationAdapter<?>> adaptersByType;

    /** 构建不可变适配器注册表并拒绝空声明、重复声明。 */
    public LightweightCodeGenerationExecutor(List<LightweightCodeGenerationAdapter<?>> adapters) {
        if (adapters == null || adapters.isEmpty()) {
            throw new IllegalStateException("轻量代码生成 adapter 列表不能为空");
        }
        EnumMap<CodeGenTypeEnum, LightweightCodeGenerationAdapter<?>> registeredAdapters =
                new EnumMap<>(CodeGenTypeEnum.class);
        for (LightweightCodeGenerationAdapter<?> adapter : adapters) {
            registerAdapter(registeredAdapters, adapter);
        }
        this.adaptersByType = Map.copyOf(registeredAdapters);
    }

    /** 判断当前生成类型是否具有轻量 provider 协议。 */
    public boolean supports(CodeGenTypeEnum codeGenType) {
        return codeGenType != null && adaptersByType.containsKey(codeGenType);
    }

    /** 通过注册的类型协议执行阻塞生成。 */
    public Object generate(AiCodeGeneratorService service,
                           CodeGenTypeEnum codeGenType,
                           String userPrompt) {
        return requireAdapter(codeGenType).generate(
                Objects.requireNonNull(service, "AI 代码生成服务不能为空"),
                userPrompt
        );
    }

    /** 通过注册的类型协议执行流式生成。 */
    public TokenStream generateStream(AiCodeGeneratorService service,
                                      CodeGenTypeEnum codeGenType,
                                      String userPrompt,
                                      InvocationParameters invocationParameters) {
        return requireAdapter(codeGenType).generateStream(
                Objects.requireNonNull(service, "AI 代码生成服务不能为空"),
                userPrompt,
                Objects.requireNonNull(invocationParameters, "模型调用参数不能为空")
        );
    }

    private LightweightCodeGenerationAdapter<?> requireAdapter(CodeGenTypeEnum codeGenType) {
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码生成类型不能为空");
        }
        LightweightCodeGenerationAdapter<?> adapter = adaptersByType.get(codeGenType);
        if (adapter == null) {
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "未注册轻量代码生成 adapter: " + codeGenType.getValue()
            );
        }
        return adapter;
    }

    private static void registerAdapter(
            Map<CodeGenTypeEnum, LightweightCodeGenerationAdapter<?>> registeredAdapters,
            LightweightCodeGenerationAdapter<?> adapter) {
        if (adapter == null) {
            throw new IllegalStateException("轻量代码生成 adapter 列表不能包含 null");
        }
        CodeGenTypeEnum codeGenType = adapter.codeGenType();
        if (codeGenType == null) {
            throw new IllegalStateException("轻量代码生成 adapter 必须声明生成类型");
        }
        LightweightCodeGenerationAdapter<?> previous =
                registeredAdapters.putIfAbsent(codeGenType, adapter);
        if (previous != null) {
            throw new IllegalStateException(
                    "生成类型存在重复轻量代码生成 adapter: " + codeGenType.getValue());
        }
    }
}
