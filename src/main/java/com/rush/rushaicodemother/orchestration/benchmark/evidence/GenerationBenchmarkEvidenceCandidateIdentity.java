package com.rush.rushaicodemother.orchestration.benchmark.evidence;

/** 从实际发布候选解析出的受信身份。 */
public record GenerationBenchmarkEvidenceCandidateIdentity(
        GenerationBenchmarkEvidenceSubject subjectType,
        String subjectKey,
        String candidateFingerprint,
        String modelFingerprint,
        String promptBundleFingerprint
) {
}
