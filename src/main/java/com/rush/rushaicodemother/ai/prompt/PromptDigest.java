package com.rush.rushaicodemother.ai.prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 提示词摘要。
 */
final class PromptDigest {

    private PromptDigest() {
    }

    static String normalizeContent(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .stripTrailing();
    }

    /** 计算内容的 SHA-256 摘要。 */
    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
