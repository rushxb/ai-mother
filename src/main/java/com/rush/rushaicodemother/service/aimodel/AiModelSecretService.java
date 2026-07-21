package com.rush.rushaicodemother.service.aimodel;

/** Protects provider credentials at write time and resolves them only at provider-call boundaries. */
public interface AiModelSecretService {

    AiModelProtectedSecret protect(String apiKey);

    String resolve(String secretReference, String expectedFingerprint);

    boolean isProtectedReference(String secretReference);

    boolean canResolve(String secretReference);

    String keyId(String secretReference);
}
