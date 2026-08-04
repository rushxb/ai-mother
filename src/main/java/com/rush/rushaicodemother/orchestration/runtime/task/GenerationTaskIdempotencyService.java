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

    /**
 * 根据当前上下文解析生成任务{@code Idempotency}。
 *
 * @param rawKey 原始键
 * @param appId 应用编号
 * @param message 消息内容
 * @return 生成任务{@code Idempotency}
 */
    public GenerationTaskIdempotency resolve(String rawKey, Long appId, String message) {
        if (rawKey == null) {
            return GenerationTaskIdempotency.none();
        }
        byte[] keyBytes = rawKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length == 0 || keyBytes.length > MAX_KEY_BYTES || !isVisibleAscii(rawKey)) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "Idempotency-Key 必须由 1 至 255 个可见 ASCII 字符组成"
            );
        }
        if (appId == null || appId <= 0 || message == null) {
            throw new IllegalArgumentException("生成任务提交指纹信息不完整");
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

    /** 判断{@code Visible}{@code Ascii}是否满足约束。 */
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

    /** 计算内容的 SHA-256 摘要。 */
    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 摘要算法不可用", impossible);
        }
    }

    private String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }
}
