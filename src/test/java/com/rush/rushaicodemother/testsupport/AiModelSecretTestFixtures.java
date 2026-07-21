package com.rush.rushaicodemother.testsupport;

import com.rush.rushaicodemother.config.AiModelSecretProperties;
import com.rush.rushaicodemother.service.aimodel.AiModelProtectedSecret;
import com.rush.rushaicodemother.service.aimodel.AiModelSecretService;
import com.rush.rushaicodemother.service.aimodel.EnvelopeAiModelSecretService;

import java.util.Base64;
import java.util.Arrays;

/** Shared cryptographic fixture for AI model unit tests. */
public final class AiModelSecretTestFixtures {

    public static final String KEY_ID = "test-kek-v1";

    private static final AiModelSecretService SECRET_SERVICE = createService();

    private AiModelSecretTestFixtures() {
    }

    public static AiModelSecretService service() {
        return SECRET_SERVICE;
    }

    public static AiModelProtectedSecret protect(String plaintext) {
        return SECRET_SERVICE.protect(plaintext);
    }

    private static AiModelSecretService createService() {
        byte[] encryptionKey = new byte[32];
        byte[] fingerprintKey = new byte[32];
        for (int index = 0; index < 32; index++) {
            encryptionKey[index] = (byte) (index + 1);
            fingerprintKey[index] = (byte) (index + 65);
        }
        AiModelSecretProperties properties = new AiModelSecretProperties();
        properties.setActiveKeyId(KEY_ID);
        properties.setActiveKey(Base64.getEncoder().encodeToString(encryptionKey));
        properties.setFingerprintKey(Base64.getEncoder().encodeToString(fingerprintKey));
        try {
            return new EnvelopeAiModelSecretService(properties);
        } finally {
            Arrays.fill(encryptionKey, (byte) 0);
            Arrays.fill(fingerprintKey, (byte) 0);
        }
    }
}
