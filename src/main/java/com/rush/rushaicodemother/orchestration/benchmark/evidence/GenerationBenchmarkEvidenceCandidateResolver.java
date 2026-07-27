package com.rush.rushaicodemother.orchestration.benchmark.evidence;

/** 按候选类型解析发布身份的策略扩展点。 */
public interface GenerationBenchmarkEvidenceCandidateResolver {

    boolean supports(GenerationBenchmarkEvidenceCandidate candidate);

    GenerationBenchmarkEvidenceCandidateIdentity resolve(
            GenerationBenchmarkEvidenceCandidate candidate);
}
