package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.config.GenerationBenchmarkWorkerProperties;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubject;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** 从已校验配置构造本次 Worker 唯一的发布候选。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.generation-benchmark.worker",
        name = "enabled",
        havingValue = "true")
public class GenerationBenchmarkWorkerCandidateProvider {

    private final GenerationBenchmarkWorkerProperties properties;

    public GenerationBenchmarkEvidenceCandidate candidate() {
        GenerationBenchmarkWorkerProperties.Candidate configured = properties.getCandidate();
        if (configured == null) {
            throw new IllegalStateException("Benchmark Worker 候选配置不能为空");
        }
        GenerationBenchmarkEvidenceSubject subject = subject(configured.getSubjectType());
        return switch (subject) {
            case AI_MODEL_ENABLE ->
                    new GenerationBenchmarkEvidenceCandidate.AiModelEnable(configured.getModelId());
            case PROMPT_RELEASE -> new GenerationBenchmarkEvidenceCandidate.PromptRelease(
                    trim(configured.getPromptKey()),
                    new PromptReleaseSpec(
                            configured.getStableVersion(),
                            configured.getCanaryVersion(),
                            configured.getCanaryPercentage()
                    )
            );
        };
    }

    private GenerationBenchmarkEvidenceSubject subject(String value) {
        try {
            return GenerationBenchmarkEvidenceSubject.valueOf(
                    trim(value).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Benchmark Worker 候选类型无效", invalid);
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
