package com.rush.rushaicodemother.orchestration.benchmark.evidence;

/** 定义 Benchmark 发布证据的签名协议及候选执行证明约束。 */
public final class GenerationBenchmarkEvidenceProtocol {

    public static final int LEGACY_SIGNATURE_VERSION = 1;
    public static final int CURRENT_SIGNATURE_VERSION = 2;

    private GenerationBenchmarkEvidenceProtocol() {
    }

    public static boolean isSupported(int signatureVersion) {
        return signatureVersion == LEGACY_SIGNATURE_VERSION
                || signatureVersion == CURRENT_SIGNATURE_VERSION;
    }

    public static boolean hasValidAttestation(int signatureVersion,
                                              GenerationBenchmarkEvidenceSubject subjectType,
                                              long candidatePhysicalRequestCount) {
        if (!isSupported(signatureVersion)
                || subjectType == null
                || candidatePhysicalRequestCount < 0) {
            return false;
        }
        if (signatureVersion == LEGACY_SIGNATURE_VERSION) {
            return candidatePhysicalRequestCount == 0;
        }
        return switch (subjectType) {
            case AI_MODEL_ENABLE -> candidatePhysicalRequestCount > 0;
            case PROMPT_RELEASE -> candidatePhysicalRequestCount == 0;
        };
    }

    public static boolean hasCurrentAttestation(
            int signatureVersion,
            GenerationBenchmarkEvidenceSubject subjectType,
            long candidatePhysicalRequestCount) {
        return signatureVersion == CURRENT_SIGNATURE_VERSION
                && hasValidAttestation(
                signatureVersion, subjectType, candidatePhysicalRequestCount);
    }
}
