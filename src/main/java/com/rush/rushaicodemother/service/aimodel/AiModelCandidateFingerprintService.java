package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.orchestration.benchmark.evidence.ReleaseCandidateFingerprint;
import org.springframework.stereotype.Component;

/** Stable, non-reversible identity for the exact model configuration proposed for enablement. */
@Component
public class AiModelCandidateFingerprintService {

    public String fingerprint(AiModelConfiguration configuration) {
        if (configuration == null || configuration.getId() == null) {
            throw new IllegalArgumentException("AI model candidate is incomplete");
        }
        StringBuilder canonical = new StringBuilder("ai-model-enable-candidate-v2|");
        ReleaseCandidateFingerprint.appendField(canonical, String.valueOf(configuration.getId()));
        ReleaseCandidateFingerprint.appendField(canonical, configuration.getModelName());
        ReleaseCandidateFingerprint.appendField(canonical, configuration.getProvider());
        ReleaseCandidateFingerprint.appendField(canonical, configuration.getModelId());
        ReleaseCandidateFingerprint.appendField(canonical, configuration.getBaseUrl());
        ReleaseCandidateFingerprint.appendField(canonical, configuration.getSecretFingerprint());
        ReleaseCandidateFingerprint.appendField(canonical, String.valueOf(configuration.getMaxTokens()));
        ReleaseCandidateFingerprint.appendField(canonical, String.valueOf(configuration.getTemperature()));
        ReleaseCandidateFingerprint.appendField(canonical, String.valueOf(configuration.getIsEnabled()));
        ReleaseCandidateFingerprint.appendField(canonical, configuration.getModelType());
        ReleaseCandidateFingerprint.appendField(canonical, String.valueOf(configuration.getSupportsThinking()));
        ReleaseCandidateFingerprint.appendField(canonical, String.valueOf(configuration.getSortOrder()));
        ReleaseCandidateFingerprint.appendField(canonical, configuration.getConfigJson());
        return ReleaseCandidateFingerprint.sha256(canonical.toString());
    }
}
