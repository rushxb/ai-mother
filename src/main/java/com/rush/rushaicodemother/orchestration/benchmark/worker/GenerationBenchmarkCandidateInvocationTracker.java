package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.rush.rushaicodemother.monitor.AiModelInvocationObserver;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import com.rush.rushaicodemother.service.aimodel.AiModelConfiguration;
import com.rush.rushaicodemother.service.aimodel.AiModelEnabledConfigurationSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/** 证明待启用模型在本轮评测中至少收到过一次真实物理请求。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.generation-benchmark.worker",
        name = "enabled",
        havingValue = "true")
public class GenerationBenchmarkCandidateInvocationTracker
        implements AiModelInvocationObserver {

    private final GenerationBenchmarkWorkerCandidateProvider candidateProvider;
    private final AiModelEnabledConfigurationSnapshot modelSnapshot;
    private final LongAdder candidateRequestCount = new LongAdder();
    private volatile boolean initialized;
    private volatile boolean tracking;
    private volatile ModelIdentity candidateIdentity;

    /**
 * 响应请求事件。
 *
 * @param provider 提供方
 * @param modelId 模型编号
 */
    @Override
    public void onRequest(String provider, String modelId) {
        if (!tracking) {
            return;
        }
        ModelIdentity required = candidateIdentity();
        if (required != null
                && required.provider().equals(normalize(provider))
                && required.modelId().equals(normalize(modelId))) {
            candidateRequestCount.increment();
        }
    }

    /**
 * 开始生成基准测试候选调用{@code Tracker}。
 *
 * @param candidate 候选
 */
    public synchronized void begin(GenerationBenchmarkEvidenceCandidate candidate) {
        requireConfiguredCandidate(candidate);
        if (tracking) {
            throw new IllegalStateException("Benchmark 候选物理请求观察窗口已开启");
        }
        candidateIdentity();
        candidateRequestCount.reset();
        tracking = true;
    }

    /**
 * 校验并返回有效的候选{@code Invoked}。
 *
 * @param candidate 候选
 * @return 计算或处理后的数值结果
 */
    public long requireCandidateInvoked(GenerationBenchmarkEvidenceCandidate candidate) {
        requireConfiguredCandidate(candidate);
        if (!tracking) {
            throw new IllegalStateException("Benchmark 候选物理请求观察窗口未开启");
        }
        long requestCount = candidateRequestCount.sum();
        if (candidate instanceof GenerationBenchmarkEvidenceCandidate.AiModelEnable
                && requestCount <= 0) {
            throw new IllegalStateException(
                    "Benchmark 模型候选在评测期间未收到真实物理请求");
        }
        return requestCount;
    }

    public void end() {
        tracking = false;
    }

    long candidateRequestCount() {
        return candidateRequestCount.sum();
    }

    private void requireConfiguredCandidate(GenerationBenchmarkEvidenceCandidate candidate) {
        if (candidate == null || !candidate.equals(candidateProvider.candidate())) {
            throw new IllegalStateException("Benchmark 物理请求观察候选与 Worker 配置不一致");
        }
    }

    /** 判断当前状态是否允许{@code didate}{@code Identity}。 */
    private ModelIdentity candidateIdentity() {
        if (initialized) {
            return candidateIdentity;
        }
        synchronized (this) {
            if (!initialized) {
                candidateIdentity = resolveCandidateIdentity();
                initialized = true;
            }
            return candidateIdentity;
        }
    }

    /** 根据当前上下文解析候选{@code Identity}。 */
    private ModelIdentity resolveCandidateIdentity() {
        GenerationBenchmarkEvidenceCandidate candidate = candidateProvider.candidate();
        if (!(candidate instanceof GenerationBenchmarkEvidenceCandidate.AiModelEnable modelCandidate)) {
            return null;
        }
        AiModelConfiguration configuration = modelSnapshot.enabledModels().stream()
                .filter(model -> model.getId() != null
                        && model.getId() == modelCandidate.modelId())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Benchmark 模型候选未进入冻结模型池"));
        return new ModelIdentity(
                normalize(configuration.getProvider()),
                normalize(configuration.getModelId())
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record ModelIdentity(String provider, String modelId) {
    }
}
