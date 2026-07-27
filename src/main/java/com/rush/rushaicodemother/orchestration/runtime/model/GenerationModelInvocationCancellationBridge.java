package com.rush.rushaicodemother.orchestration.runtime.model;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.observability.api.event.AiServiceRequestIssuedEvent;
import dev.langchain4j.observability.api.listener.AiServiceRequestIssuedListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 将 LangChain4j invocation 与每一轮实际模型请求的取消域关联起来。 */
@Component
public class GenerationModelInvocationCancellationBridge {

    private final Cache<ChatRequest, GenerationModelCancellationScope> pendingRequests =
            Caffeine.newBuilder()
                    .weakKeys()
                    .maximumSize(10_000)
                    .expireAfterWrite(Duration.ofMinutes(5))
                    .build();
    private final ThreadLocal<GenerationModelCancellationScope> activeScope = new ThreadLocal<>();
    private final AiServiceRequestIssuedListener requestIssuedListener = this::onRequestIssued;

    public AiServiceRequestIssuedListener requestIssuedListener() {
        return requestIssuedListener;
    }

    public ScopeBinding bind(ChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("模型请求不能为空");
        }
        GenerationModelCancellationScope scope = pendingRequests.asMap().remove(request);
        if (scope == null) {
            scope = activeScope.get();
        }
        return activate(scope);
    }

    public ScopeBinding activate(GenerationModelCancellationScope scope) {
        GenerationModelCancellationScope previous = activeScope.get();
        if (scope == null) {
            return new ScopeBinding(this, null, previous, false);
        }
        activeScope.set(scope);
        return new ScopeBinding(this, scope, previous, true);
    }

    public void registerTransportCancellation(GenerationCancellationHandle handle) {
        GenerationModelCancellationScope scope = activeScope.get();
        if (scope != null) {
            scope.register(handle);
        }
    }

    private void onRequestIssued(AiServiceRequestIssuedEvent event) {
        if (event == null || event.request() == null) {
            return;
        }
        InvocationContext context = event.invocationContext();
        InvocationParameters parameters = context == null ? null : context.invocationParameters();
        if (parameters == null) {
            return;
        }
        Object value = parameters.get(GenerationModelCancellationScope.INVOCATION_PARAMETER);
        if (value instanceof GenerationModelCancellationScope scope) {
            pendingRequests.put(event.request(), scope);
        }
    }

    private void restore(GenerationModelCancellationScope previous) {
        if (previous == null) {
            activeScope.remove();
        } else {
            activeScope.set(previous);
        }
    }

    public static final class ScopeBinding implements AutoCloseable {

        private final GenerationModelInvocationCancellationBridge bridge;
        private final GenerationModelCancellationScope scope;
        private final GenerationModelCancellationScope previous;
        private final boolean activated;
        private boolean closed;

        private ScopeBinding(GenerationModelInvocationCancellationBridge bridge,
                             GenerationModelCancellationScope scope,
                             GenerationModelCancellationScope previous,
                             boolean activated) {
            this.bridge = bridge;
            this.scope = scope;
            this.previous = previous;
            this.activated = activated;
        }

        public GenerationModelCancellationScope scope() {
            return scope;
        }

        @Override
        public void close() {
            if (!closed && activated) {
                closed = true;
                bridge.restore(previous);
            }
        }
    }
}
