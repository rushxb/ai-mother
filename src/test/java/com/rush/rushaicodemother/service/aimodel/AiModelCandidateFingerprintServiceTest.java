package com.rush.rushaicodemother.service.aimodel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AiModelCandidateFingerprintServiceTest {

    private final AiModelCandidateFingerprintService service =
            new AiModelCandidateFingerprintService();

    @Test
    void randomizedCiphertextMustNotChangeReleaseCandidateIdentity() {
        String fingerprint = "a".repeat(64);
        AiModelConfiguration first = configuration("enc:v1:k1:first", fingerprint);
        AiModelConfiguration second = configuration("enc:v1:k1:second", fingerprint);

        assertEquals(service.fingerprint(first), service.fingerprint(second));
    }

    @Test
    void credentialRotationMustChangeReleaseCandidateIdentity() {
        AiModelConfiguration first = configuration("enc:v1:k1:first", "a".repeat(64));
        AiModelConfiguration second = configuration("enc:v1:k1:second", "b".repeat(64));

        assertNotEquals(service.fingerprint(first), service.fingerprint(second));
    }

    private AiModelConfiguration configuration(String reference, String fingerprint) {
        return AiModelConfiguration.builder()
                .id(7L)
                .modelName("Model")
                .provider("custom")
                .modelId("model-id")
                .baseUrl("https://models.example.com/v1")
                .secretRef(reference)
                .secretFingerprint(fingerprint)
                .secretKeyId("k1")
                .maxTokens(4096)
                .temperature(0.7)
                .isEnabled(1)
                .modelType("chat")
                .supportsThinking(0)
                .sortOrder(0)
                .configJson("{\"protocol\":\"openai_chat_completions\"}")
                .build();
    }
}
