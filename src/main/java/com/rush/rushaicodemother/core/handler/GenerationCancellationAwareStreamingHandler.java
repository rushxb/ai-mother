package com.rush.rushaicodemother.core.handler;

import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.Objects;

/**
 * 允许流式模型包装层在首个供应商 handle 出现前注册取消能力。
 */
public interface GenerationCancellationAwareStreamingHandler extends StreamingChatResponseHandler {

    void registerCancellationHandle(GenerationCancellationHandle cancellationHandle);

    /**
 * 注册{@code If}支持的。
 *
 * @param handler 处理器
 * @param cancellationHandle {@code cancellationHandle} 对应的调用参数
 */
    static void registerIfSupported(StreamingChatResponseHandler handler,
                                    GenerationCancellationHandle cancellationHandle) {
        Objects.requireNonNull(handler, "流式响应处理器不能为空");
        Objects.requireNonNull(cancellationHandle, "生成取消句柄不能为空");
        if (handler instanceof GenerationCancellationAwareStreamingHandler awareHandler) {
            awareHandler.registerCancellationHandle(cancellationHandle);
        }
    }
}
