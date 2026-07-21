package com.rush.rushaicodemother.orchestration.runtime.task;

/** Hashed submission identity; raw {@code Idempotency-Key} values are never persisted. */
public record GenerationTaskIdempotency(
        String keyHash,
        String requestFingerprint
) {

    private static final String SHA_256_PATTERN = "[0-9a-f]{64}";
    private static final GenerationTaskIdempotency NONE = new GenerationTaskIdempotency(null, null);

    public GenerationTaskIdempotency {
        if ((keyHash == null) != (requestFingerprint == null)) {
            throw new IllegalArgumentException("idempotency key hash and request fingerprint must be paired");
        }
        if (keyHash != null
                && (!keyHash.matches(SHA_256_PATTERN) || !requestFingerprint.matches(SHA_256_PATTERN))) {
            throw new IllegalArgumentException("idempotency hashes must be lowercase SHA-256 values");
        }
    }

    public static GenerationTaskIdempotency none() {
        return NONE;
    }

    public boolean present() {
        return keyHash != null;
    }
}
