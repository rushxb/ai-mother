package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.config.AiModelSecretProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** AES-256-GCM envelope encryption with per-secret data keys and key-id based rotation. */
@Service
public class EnvelopeAiModelSecretService implements AiModelSecretService {

    static final String REFERENCE_PREFIX = "enc:v1:";
    private static final String KEY_ALGORITHM = "AES";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final Base64.Encoder REFERENCE_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder REFERENCE_DECODER = Base64.getUrlDecoder();

    private final AiModelSecretProperties properties;
    private final SecureRandom secureRandom;
    private final Map<String, SecretKey> keyRing;
    private final SecretKey activeKey;
    private final String activeKeyId;
    private final SecretKey fingerprintKey;

    @Autowired
    public EnvelopeAiModelSecretService(AiModelSecretProperties properties) {
        this(properties, new SecureRandom());
    }

    EnvelopeAiModelSecretService(AiModelSecretProperties properties, SecureRandom secureRandom) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.activeKeyId = normalizeKeyId(properties.getActiveKeyId(), false);
        Map<String, SecretKey> configuredKeys = new LinkedHashMap<>();
        if (properties.getPreviousKeys() != null) {
            properties.getPreviousKeys().forEach((keyId, encodedKey) -> {
                String normalizedKeyId = normalizeKeyId(keyId, true);
                SecretKey previous = configuredKeys.putIfAbsent(
                        normalizedKeyId, decodeKey(encodedKey, "previous encryption key"));
                if (previous != null) {
                    throw new IllegalStateException(
                            "AI model secret previous key ids must be unique after normalization");
                }
            });
        }
        if (activeKeyId != null || hasText(properties.getActiveKey())) {
            if (activeKeyId == null || !hasText(properties.getActiveKey())) {
                throw new IllegalStateException(
                        "AI model secret active key id and key must be configured together");
            }
            if (configuredKeys.containsKey(activeKeyId)) {
                throw new IllegalStateException(
                        "AI model secret active key id must not also be configured as a previous key");
            }
            configuredKeys.put(activeKeyId, decodeKey(properties.getActiveKey(), "active encryption key"));
        }
        this.keyRing = Map.copyOf(configuredKeys);
        this.activeKey = activeKeyId == null ? null : keyRing.get(activeKeyId);
        this.fingerprintKey = hasText(properties.getFingerprintKey())
                ? decodeHmacKey(properties.getFingerprintKey())
                : null;
        requireSeparatedKeyPurposes();
    }

    @Override
    public AiModelProtectedSecret protect(String apiKey) {
        requireWriteKeys();
        String normalized = apiKey == null ? null : apiKey.trim();
        if (!hasText(normalized)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "AI model API key cannot be blank");
        }
        byte[] secretBytes = normalized.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length > properties.getMaxSecretBytes()) {
            Arrays.fill(secretBytes, (byte) 0);
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "AI model API key is too large");
        }

        byte[] dataKeyBytes = randomBytes(KEY_BYTES);
        byte[] wrapNonce = randomBytes(NONCE_BYTES);
        byte[] secretNonce = randomBytes(NONCE_BYTES);
        try {
            SecretKey dataKey = new SecretKeySpec(dataKeyBytes, KEY_ALGORITHM);
            byte[] wrappedDataKey = encrypt(
                    activeKey, wrapNonce, dataKeyBytes, wrapAad(activeKeyId));
            byte[] encryptedSecret = encrypt(
                    dataKey, secretNonce, secretBytes, secretAad(activeKeyId));
            String reference = REFERENCE_PREFIX
                    + activeKeyId + ':'
                    + encode(wrapNonce) + ':'
                    + encode(wrappedDataKey) + ':'
                    + encode(secretNonce) + ':'
                    + encode(encryptedSecret);
            if (reference.getBytes(StandardCharsets.UTF_8).length > properties.getMaxReferenceBytes()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "Protected AI model secret reference is too large");
            }
            return new AiModelProtectedSecret(
                    reference,
                    fingerprint(secretBytes),
                    activeKeyId
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (GeneralSecurityException exception) {
            throw secretOperationFailure("AI model API key encryption failed", exception);
        } finally {
            Arrays.fill(secretBytes, (byte) 0);
            Arrays.fill(dataKeyBytes, (byte) 0);
        }
    }

    @Override
    public String resolve(String secretReference, String expectedFingerprint) {
        ParsedReference parsed = parse(secretReference);
        SecretKey keyEncryptionKey = keyRing.get(parsed.keyId());
        if (keyEncryptionKey == null || fingerprintKey == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "AI model secret encryption key is unavailable");
        }
        byte[] dataKeyBytes = null;
        byte[] secretBytes = null;
        try {
            dataKeyBytes = decrypt(
                    keyEncryptionKey,
                    parsed.wrapNonce(),
                    parsed.wrappedDataKey(),
                    wrapAad(parsed.keyId()));
            if (dataKeyBytes.length != KEY_BYTES) {
                throw new GeneralSecurityException("wrapped data key length is invalid");
            }
            SecretKey dataKey = new SecretKeySpec(dataKeyBytes, KEY_ALGORITHM);
            secretBytes = decrypt(
                    dataKey,
                    parsed.secretNonce(),
                    parsed.encryptedSecret(),
                    secretAad(parsed.keyId()));
            if (secretBytes.length == 0 || secretBytes.length > properties.getMaxSecretBytes()) {
                throw new GeneralSecurityException("decrypted secret length is invalid");
            }
            requireMatchingFingerprint(secretBytes, expectedFingerprint);
            return decodeUtf8(secretBytes);
        } catch (GeneralSecurityException | CharacterCodingException exception) {
            throw secretOperationFailure("AI model API key decryption failed", exception);
        } finally {
            if (dataKeyBytes != null) {
                Arrays.fill(dataKeyBytes, (byte) 0);
            }
            if (secretBytes != null) {
                Arrays.fill(secretBytes, (byte) 0);
            }
        }
    }

    @Override
    public boolean isProtectedReference(String secretReference) {
        try {
            parse(secretReference);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public boolean canResolve(String secretReference) {
        try {
            return fingerprintKey != null && keyRing.containsKey(parse(secretReference).keyId());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public String keyId(String secretReference) {
        return parse(secretReference).keyId();
    }

    private ParsedReference parse(String reference) {
        if (!hasText(reference)
                || reference.getBytes(StandardCharsets.UTF_8).length > properties.getMaxReferenceBytes()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "AI model secret reference is invalid");
        }
        String[] parts = reference.split(":", -1);
        if (parts.length != 7 || !"enc".equals(parts[0]) || !"v1".equals(parts[1])) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "AI model secret reference format is unsupported");
        }
        String keyId;
        try {
            keyId = normalizeKeyId(parts[2], true);
        } catch (IllegalStateException exception) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "AI model secret reference is malformed", exception);
        }
        byte[] wrapNonce = decodeReferencePart(parts[3], NONCE_BYTES, NONCE_BYTES);
        byte[] wrappedDataKey = decodeReferencePart(parts[4], KEY_BYTES + 16, KEY_BYTES + 16);
        byte[] secretNonce = decodeReferencePart(parts[5], NONCE_BYTES, NONCE_BYTES);
        byte[] encryptedSecret = decodeReferencePart(
                parts[6], 17, properties.getMaxSecretBytes() + 16);
        return new ParsedReference(
                keyId, wrapNonce, wrappedDataKey, secretNonce, encryptedSecret);
    }

    private byte[] encrypt(SecretKey key,
                           byte[] nonce,
                           byte[] plaintext,
                           byte[] aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    private byte[] decrypt(SecretKey key,
                           byte[] nonce,
                           byte[] ciphertext,
                           byte[] aad) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext);
    }

    private String fingerprint(byte[] secretBytes) throws GeneralSecurityException {
        byte[] digest = fingerprintBytes(secretBytes);
        try {
            return HexFormat.of().formatHex(digest);
        } finally {
            Arrays.fill(digest, (byte) 0);
        }
    }

    private byte[] fingerprintBytes(byte[] secretBytes) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(fingerprintKey);
        return mac.doFinal(secretBytes);
    }

    private void requireMatchingFingerprint(byte[] secretBytes,
                                            String expectedFingerprint) throws GeneralSecurityException {
        if (!hasText(expectedFingerprint) || !expectedFingerprint.matches("[a-f0-9]{64}")) {
            throw new GeneralSecurityException("secret fingerprint metadata is invalid");
        }
        byte[] actual = fingerprintBytes(secretBytes);
        byte[] expected = HexFormat.of().parseHex(expectedFingerprint);
        try {
            if (!MessageDigest.isEqual(actual, expected)) {
                throw new GeneralSecurityException("secret fingerprint does not match ciphertext");
            }
        } finally {
            Arrays.fill(actual, (byte) 0);
            Arrays.fill(expected, (byte) 0);
        }
    }

    private byte[] wrapAad(String keyId) {
        return ("ai-model-secret-dek|v1|" + keyId).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] secretAad(String keyId) {
        return ("ai-model-secret-value|v1|" + keyId).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        secureRandom.nextBytes(value);
        return value;
    }

    private String encode(byte[] value) {
        return REFERENCE_ENCODER.encodeToString(value);
    }

    private byte[] decodeReferencePart(String value, int minimumLength, int maximumLength) {
        try {
            byte[] decoded = REFERENCE_DECODER.decode(value);
            if (decoded.length < minimumLength || decoded.length > maximumLength) {
                throw new IllegalArgumentException("reference part length is invalid");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "AI model secret reference is malformed", exception);
        }
    }

    private SecretKey decodeKey(String encoded, String label) {
        byte[] decoded = decodeConfiguredKey(encoded, label);
        try {
            return new SecretKeySpec(decoded, KEY_ALGORITHM);
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    private SecretKey decodeHmacKey(String encoded) {
        byte[] decoded = decodeConfiguredKey(encoded, "fingerprint key");
        try {
            return new SecretKeySpec(decoded, HMAC_ALGORITHM);
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    private byte[] decodeConfiguredKey(String encoded, String label) {
        if (!hasText(encoded)) {
            throw new IllegalStateException("AI model secret " + label + " is blank");
        }
        try {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(encoded.trim());
            } catch (IllegalArgumentException standardFailure) {
                decoded = Base64.getUrlDecoder().decode(encoded.trim());
            }
            if (decoded.length != KEY_BYTES) {
                Arrays.fill(decoded, (byte) 0);
                throw new IllegalStateException(
                        "AI model secret " + label + " must decode to 256 bits");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "AI model secret " + label + " must be valid Base64", exception);
        }
    }

    private String normalizeKeyId(String keyId, boolean required) {
        String normalized = keyId == null ? null : keyId.trim();
        if (!hasText(normalized)) {
            if (required) {
                throw new IllegalStateException("AI model secret key id is required");
            }
            return null;
        }
        if (!normalized.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalStateException("AI model secret key id format is invalid");
        }
        return normalized;
    }

    private String decodeUtf8(byte[] value) throws CharacterCodingException {
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value));
        return decoded.toString();
    }

    private void requireWriteKeys() {
        if (activeKey == null || activeKeyId == null || fingerprintKey == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "AI model secret encryption is not configured");
        }
    }

    private void requireSeparatedKeyPurposes() {
        if (fingerprintKey == null) {
            return;
        }
        byte[] fingerprintBytes = fingerprintKey.getEncoded();
        try {
            for (SecretKey encryptionKey : keyRing.values()) {
                byte[] encryptionBytes = encryptionKey.getEncoded();
                try {
                    if (MessageDigest.isEqual(fingerprintBytes, encryptionBytes)) {
                        throw new IllegalStateException(
                                "AI model encryption and fingerprint keys must be different");
                    }
                } finally {
                    Arrays.fill(encryptionBytes, (byte) 0);
                }
            }
        } finally {
            Arrays.fill(fingerprintBytes, (byte) 0);
        }
    }

    private BusinessException secretOperationFailure(String message, Exception cause) {
        return new BusinessException(ErrorCode.OPERATION_ERROR, message, cause);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ParsedReference(
            String keyId,
            byte[] wrapNonce,
            byte[] wrappedDataKey,
            byte[] secretNonce,
            byte[] encryptedSecret
    ) {
    }
}
