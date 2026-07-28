package com.rush.rushaicodemother.service.aimodel;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.config.AiModelCircuitBreakerProperties;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.model.event.AiModelCircuitOpenedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/** 运行时模型路由器使用的每模型断路器。 */
@Component
public class AiModelCircuitBreaker {
    private final AiModelCircuitBreakerProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final Cache<String, CircuitState> states;

    @Autowired
    public AiModelCircuitBreaker(AiModelCircuitBreakerProperties properties,
                                 ApplicationEventPublisher eventPublisher) {
        this(properties, eventPublisher, Clock.systemUTC());
    }

    AiModelCircuitBreaker(AiModelCircuitBreakerProperties properties,
                          ApplicationEventPublisher eventPublisher,
                          Clock clock) {
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.states = Caffeine.newBuilder().maximumSize(properties.getMaxTrackedModels()).build();
    }

    public boolean isAvailable(String modelId) {
        return isAvailable("unknown", modelId);
    }

    public boolean isAvailable(String provider, String modelId) {
        CircuitState state = states.getIfPresent(identity(provider, modelId));
        return state == null || state.isAvailable(clock.instant());
    }

    public void recordSuccess(String modelId) {
        recordSuccess("unknown", modelId);
    }

    /**
 * 记录成功相关指标或状态。
 *
 * @param provider 提供方
 * @param modelId 模型编号
 */
    public void recordSuccess(String provider, String modelId) {
        states.invalidate(identity(provider, modelId));
    }

    public void recordFailure(String modelId, Throwable failure) {
        recordFailure("unknown", modelId, failure);
    }

    /**
 * 记录失败相关指标或状态。
 *
 * @param provider 提供方
 * @param modelId 模型编号
 * @param failure 失败
 */
    public void recordFailure(String provider, String modelId, Throwable failure) {
        String key = identity(provider, modelId);
        GenerationErrorClassifier.GenerationError error = GenerationErrorClassifier.classify(failure);
        if (GenerationErrorClassifier.CATEGORY_MODEL_CANCELLED.equals(error.category())) {
            return;
        }
        CircuitState state = states.get(key, ignored -> new CircuitState());
        boolean opened = state.recordFailure(
                clock.instant(),
                properties.getFailureThreshold(),
                properties.getOpenDuration(),
                !error.recoverable()
        );
        if (opened) {
            eventPublisher.publishEvent(new AiModelCircuitOpenedEvent(normalize(provider), normalize(modelId)));
        }
    }

    private String identity(String provider, String modelId) {
        return normalize(provider) + "/" + normalize(modelId);
    }

    private String normalize(String modelId) {
        return modelId == null || modelId.isBlank()
                ? "unknown"
                : modelId.trim().toLowerCase(Locale.ROOT);
    }

    private static final class CircuitState {
        private int failures;
        private Instant openedUntil;

        private synchronized boolean isAvailable(Instant now) {
            return openedUntil == null || !now.isBefore(openedUntil);
        }

        /** 记录失败相关指标或状态。 */
        private synchronized boolean recordFailure(Instant now,
                                                   int threshold,
                                                   java.time.Duration openDuration,
                                                   boolean immediateOpen) {
            if (openedUntil != null && !now.isBefore(openedUntil)) {
                failures = Math.max(0, threshold - 1);
                openedUntil = null;
            }
            failures = immediateOpen ? threshold : failures + 1;
            if (failures < threshold) {
                return false;
            }
            boolean newlyOpened = openedUntil == null || !now.isBefore(openedUntil);
            openedUntil = now.plus(openDuration);
            return newlyOpened;
        }
    }
}
