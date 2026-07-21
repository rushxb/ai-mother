package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.config.AiModelSecretProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvelopeAiModelSecretServiceTest {

    @Test
    void roundTripMustPreserveCredentialWithoutPersistingPlaintext() {
        EnvelopeAiModelSecretService service = service("kek-v1", 1, 65);

        AiModelProtectedSecret protectedSecret = service.protect("  provider-secret  ");

        assertTrue(protectedSecret.reference().startsWith("enc:v1:kek-v1:"));
        assertFalse(protectedSecret.reference().contains("provider-secret"));
        assertEquals("provider-secret", service.resolve(
                protectedSecret.reference(), protectedSecret.fingerprint()));
        assertEquals("kek-v1", service.keyId(protectedSecret.reference()));
    }

    @Test
    void repeatedProtectionMustRandomizeCiphertextButKeepStableFingerprint() {
        EnvelopeAiModelSecretService service = service("kek-v1", 1, 65);

        AiModelProtectedSecret first = service.protect("same-secret");
        AiModelProtectedSecret second = service.protect("same-secret");

        assertNotEquals(first.reference(), second.reference());
        assertEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void tamperedCiphertextMustFailAuthenticationWithoutLeakingSecret() {
        EnvelopeAiModelSecretService service = service("kek-v1", 1, 65);
        AiModelProtectedSecret protectedSecret = service.protect("do-not-leak");
        String[] parts = protectedSecret.reference().split(":", -1);
        byte[] ciphertext = Base64.getUrlDecoder().decode(parts[6]);
        ciphertext[0] ^= 0x01;
        parts[6] = Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.resolve(String.join(":", parts), protectedSecret.fingerprint())
        );

        assertFalse(exception.getMessage().contains("do-not-leak"));
    }

    @Test
    void ciphertextAndPersistedFingerprintMustBeCryptographicallyBound() {
        EnvelopeAiModelSecretService service = service("kek-v1", 1, 65);
        AiModelProtectedSecret first = service.protect("first-secret");
        AiModelProtectedSecret second = service.protect("second-secret");

        assertThrows(BusinessException.class,
                () -> service.resolve(first.reference(), second.fingerprint()));
    }

    @Test
    void previousKeyRingMustResolveReferencesCreatedBeforeRotation() {
        EnvelopeAiModelSecretService oldService = service("kek-v1", 1, 65);
        AiModelProtectedSecret oldReference = oldService.protect("rotated-secret");

        AiModelSecretProperties rotated = properties("kek-v2", 33, 65);
        rotated.setPreviousKeys(new LinkedHashMap<>());
        rotated.getPreviousKeys().put("kek-v1", encodedKey(1));
        EnvelopeAiModelSecretService newService = new EnvelopeAiModelSecretService(rotated);

        assertTrue(newService.canResolve(oldReference.reference()));
        assertEquals("rotated-secret", newService.resolve(
                oldReference.reference(), oldReference.fingerprint()));
        assertEquals("kek-v2", newService.protect("new-secret").keyId());
    }

    @Test
    void unknownOrMalformedKeyIdMustFailClosedWithSafeBusinessErrors() {
        EnvelopeAiModelSecretService service = service("kek-v1", 1, 65);
        String[] parts = service.protect("secret").reference().split(":", -1);
        parts[2] = "unknown-key";

        assertThrows(BusinessException.class,
                () -> service.resolve(String.join(":", parts), "a".repeat(64)));

        parts[2] = "../invalid";
        assertThrows(BusinessException.class,
                () -> service.keyId(String.join(":", parts)));
    }

    @Test
    void missingConfigurationAndSizeViolationsMustFailClosed() {
        EnvelopeAiModelSecretService unconfigured =
                new EnvelopeAiModelSecretService(new AiModelSecretProperties());
        assertThrows(BusinessException.class, () -> unconfigured.protect("secret"));

        AiModelSecretProperties tooSmall = properties("kek-v1", 1, 65);
        tooSmall.setMaxSecretBytes(4);
        assertThrows(BusinessException.class,
                () -> new EnvelopeAiModelSecretService(tooSmall).protect("12345"));

        AiModelSecretProperties referenceLimited = properties("kek-v1", 1, 65);
        referenceLimited.setMaxReferenceBytes(100);
        assertThrows(BusinessException.class,
                () -> new EnvelopeAiModelSecretService(referenceLimited).protect("secret"));
    }

    @Test
    void encryptionAndFingerprintKeyPurposesMustRemainSeparated() {
        AiModelSecretProperties properties = properties("kek-v1", 1, 65);
        properties.setFingerprintKey(properties.getActiveKey());

        assertThrows(IllegalStateException.class,
                () -> new EnvelopeAiModelSecretService(properties));
    }

    @Test
    void protectedSecretStringRepresentationMustBeRedacted() {
        AiModelProtectedSecret secret = service("kek-v1", 1, 65).protect("secret-value");

        assertFalse(secret.toString().contains(secret.reference()));
        assertFalse(secret.toString().contains(secret.fingerprint()));
        assertFalse(secret.toString().contains("secret-value"));
    }

    private EnvelopeAiModelSecretService service(String keyId, int keyStart, int fingerprintStart) {
        return new EnvelopeAiModelSecretService(properties(keyId, keyStart, fingerprintStart));
    }

    private AiModelSecretProperties properties(String keyId, int keyStart, int fingerprintStart) {
        AiModelSecretProperties properties = new AiModelSecretProperties();
        properties.setActiveKeyId(keyId);
        properties.setActiveKey(encodedKey(keyStart));
        properties.setFingerprintKey(encodedKey(fingerprintStart));
        return properties;
    }

    private String encodedKey(int start) {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) (start + index);
        }
        return Base64.getEncoder().encodeToString(key);
    }
}
