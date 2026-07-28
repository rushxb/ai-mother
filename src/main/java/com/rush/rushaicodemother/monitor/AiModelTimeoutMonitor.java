package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCallTimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 统一记录由项目监督器主动判定的物理模型超时。 */
@Component
@RequiredArgsConstructor
public class AiModelTimeoutMonitor {

    private final AiModelMetricsCollector metricsCollector;

    /**
 * 记录 AI 模型超时{@code Monitor}相关指标或状态。
 *
 * @param provider 提供方
 * @param modelId 模型编号
 * @param failure 失败
 */
    public void record(String provider,
                       String modelId,
                       GenerationModelCallTimeoutException failure) {
        if (failure == null) {
            return;
        }
        metricsCollector.recordModelTimeout(provider, modelId, failure.timeoutKind());
    }
}
