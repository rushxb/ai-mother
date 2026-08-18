package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 用于确定性发布候选者身份的共享 SHA-256 原语。 */
public final class ReleaseCandidateFingerprint {

    private ReleaseCandidateFingerprint() {
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
 * 追加{@code Field}。
 *
 * @param target 目标对象
 * @param value 待处理值
 */
    public static void appendField(StringBuilder target, String value) {
        String normalized = value == null ? "" : value;
        target.append(normalized.length()).append(':').append(normalized).append('|');
    }
}
