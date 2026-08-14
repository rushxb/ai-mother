package com.rush.rushaicodemother.ai.model;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在物理 provider seam 直接执行调用生命周期。
 *
 * <p>LangChain4j 默认会吞掉 listener 异常，因此不能用它保证“账本成功后才发请求”。
 * 该对象由 capacity adapter 直接调用：开始失败向上抛出并阻止 provider，终态回调失败则保留
 * STARTED 以便 reconciler 恢复，不覆盖已获得的模型结果。</p>
 */
@Slf4j
public final class PhysicalModelInvocation {

    private static final PhysicalModelInvocation UNOBSERVED =
            new PhysicalModelInvocation(null, null, null, Map.of());

    private final ChatModelListener listener;
    private final ChatRequest request;
    private final ModelProvider provider;
    private final Map<Object, Object> attributes;

    private PhysicalModelInvocation(ChatModelListener listener,
                                    ChatRequest request,
                                    ModelProvider provider,
                                    Map<Object, Object> attributes) {
        this.listener = listener;
        this.request = request;
        this.provider = provider;
        this.attributes = attributes;
    }

    public static PhysicalModelInvocation start(ChatModelListener listener,
                                                ChatRequest request,
                                                ModelProvider provider) {
        if (listener == null) {
            return UNOBSERVED;
        }
        Map<Object, Object> attributes = new ConcurrentHashMap<>();
        listener.onRequest(new ChatModelRequestContext(request, provider, attributes));
        return new PhysicalModelInvocation(listener, request, provider, attributes);
    }

    public void complete(ChatResponse response) {
        if (listener == null) {
            return;
        }
        try {
            listener.onResponse(new ChatModelResponseContext(response, request, provider, attributes));
        } catch (RuntimeException terminalFailure) {
            log.warn("Physical model success ledger completion failed; STARTED entry remains pending: {}",
                    LogExceptionSanitizer.sanitize(terminalFailure));
        }
    }

    public void fail(Throwable failure) {
        if (listener == null) {
            return;
        }
        try {
            listener.onError(new ChatModelErrorContext(failure, request, provider, attributes));
        } catch (RuntimeException terminalFailure) {
            log.warn("Physical model failure ledger completion failed; STARTED entry remains pending: {}",
                    LogExceptionSanitizer.sanitize(terminalFailure));
        }
    }
}
