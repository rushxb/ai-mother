package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCallTimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 统一记录由项目监督器主动判定的物理模型超时。 */
@Component
@RequiredArgsConstructor
public class AiModelTimeoutMonitor {

    private final AiModelMetricsCollector metricsCollector;

    public void record(String provider,
                       String modelId,
                       GenerationModelCallTimeoutException failure) {
        if (failure == null) {
            return;
        }
        metricsCollector.recordModelTimeout(provider, modelId, failure.timeoutKind());
    }
}
