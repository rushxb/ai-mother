package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;

/** Benchmark Worker 支持的发布候选定义，候选指纹由控制面根据实际状态计算。 */
public sealed interface GenerationBenchmarkEvidenceCandidate
        permits GenerationBenchmarkEvidenceCandidate.AiModelEnable,
        GenerationBenchmarkEvidenceCandidate.PromptRelease {

    GenerationBenchmarkEvidenceSubject subjectType();

    String subjectKey();

    record AiModelEnable(long modelId) implements GenerationBenchmarkEvidenceCandidate {

        @Override
        public GenerationBenchmarkEvidenceSubject subjectType() {
            return GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE;
        }

        @Override
        public String subjectKey() {
            return Long.toString(modelId);
        }
    }

    record PromptRelease(String promptKey, PromptReleaseSpec release)
            implements GenerationBenchmarkEvidenceCandidate {

        @Override
        public GenerationBenchmarkEvidenceSubject subjectType() {
            return GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE;
        }

        @Override
        public String subjectKey() {
            return promptKey;
        }
    }
}
