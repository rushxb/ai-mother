package com.rush.rushaicodemother.service.aimodel;

/** Minimal persistence projection used while removing legacy plaintext provider credentials. */
public record AiModelSecretMigrationRecord(
        long modelId,
        String secretRef,
        String secretFingerprint,
        String secretKeyId,
        boolean deleted
) {

    public AiModelSecretMigrationRecord {
        if (modelId <= 0) {
            throw new IllegalArgumentException("AI model secret migration record id must be positive");
        }
    }

    @Override
    public String toString() {
        return "AiModelSecretMigrationRecord[modelId=" + modelId
                + ", secretRef=<redacted>, secretFingerprint=<redacted>, secretKeyId="
                + secretKeyId + ", deleted=" + deleted + ']';
    }
}
