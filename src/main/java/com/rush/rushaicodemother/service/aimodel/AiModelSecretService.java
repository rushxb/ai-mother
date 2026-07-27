package com.rush.rushaicodemother.service.aimodel;

/** 在写入时保护提供者凭据，并仅在提供者调用边界解析它们。 */
public interface AiModelSecretService {

    AiModelProtectedSecret protect(String apiKey);

    String resolve(String secretReference, String expectedFingerprint);

    boolean isProtectedReference(String secretReference);

    boolean canResolve(String secretReference);

    String keyId(String secretReference);
}
