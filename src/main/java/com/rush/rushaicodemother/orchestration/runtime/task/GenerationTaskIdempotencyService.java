package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 验证传输幂等性密钥并派生稳定的、不可逆的提交哈希值。 */
@Component
public class GenerationTaskIdempotencyService {

    private static final int MAX_KEY_BYTES = 255;
    private static final byte[] FINGERPRINT_NAMESPACE =
            "generation-task-submission:v1".getBytes(StandardCharsets.UTF_8);

    public GenerationTaskIdempotency resolve(String rawKey, Long appId, String message) {
        if (rawKey == null) {
            return GenerationTaskIdempotency.none();
        }
        byte[] keyBytes = rawKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length == 0 || keyBytes.length > MAX_KEY_BYTES || !isVisibleAscii(rawKey)) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "Idempotency-Key must contain 1 to 255 visible ASCII characters"
            );
        }
        if (appId == null || appId <= 0 || message == null) {
            throw new IllegalArgumentException("generation submission fingerprint identity is incomplete");
        }

        MessageDigest requestDigest = sha256();
        updateLengthPrefixed(requestDigest, FINGERPRINT_NAMESPACE);
        requestDigest.update(ByteBuffer.allocate(Long.BYTES).putLong(appId).array());
        updateLengthPrefixed(requestDigest, message.getBytes(StandardCharsets.UTF_8));
        return new GenerationTaskIdempotency(
                hex(sha256().digest(keyBytes)),
                hex(requestDigest.digest())
        );
    }

    private boolean isVisibleAscii(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                return false;
            }
        }
        return true;
    }

    private void updateLengthPrefixed(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }
}
