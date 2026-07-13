package com.rush.rushaicodemother.security.password;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * BCrypt 密码哈希实现，同时兼容历史固定盐 MD5 哈希的平滑迁移。
 */
@Component
public class BcryptPasswordHashService implements PasswordHashService {

    static final int BCRYPT_STRENGTH = 12;
    static final int BCRYPT_MAX_BYTES = 72;

    private static final String LEGACY_MD5_SALT = "rush";
    private static final Pattern LEGACY_MD5_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");
    private static final Pattern BCRYPT_PATTERN = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$.{53}$");

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    @Override
    public String hash(String rawPassword) {
        validateRawPassword(rawPassword);
        return passwordEncoder.encode(rawPassword);
    }

    @Override
    public PasswordVerificationResult verify(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null || storedHash.isBlank()) {
            return PasswordVerificationResult.failed();
        }
        if (BCRYPT_PATTERN.matcher(storedHash).matches()) {
            return verifyBcrypt(rawPassword, storedHash);
        }
        if (LEGACY_MD5_PATTERN.matcher(storedHash).matches()) {
            return verifyLegacyMd5(rawPassword, storedHash);
        }
        return PasswordVerificationResult.failed();
    }

    private PasswordVerificationResult verifyBcrypt(String rawPassword, String storedHash) {
        try {
            boolean matched = passwordEncoder.matches(rawPassword, storedHash);
            return matched
                    ? PasswordVerificationResult.matched(passwordEncoder.upgradeEncoding(storedHash))
                    : PasswordVerificationResult.failed();
        } catch (IllegalArgumentException exception) {
            return PasswordVerificationResult.failed();
        }
    }

    private PasswordVerificationResult verifyLegacyMd5(String rawPassword, String storedHash) {
        String expectedHash = DigestUtils.md5DigestAsHex(
                (rawPassword + LEGACY_MD5_SALT).getBytes(StandardCharsets.UTF_8)
        );
        byte[] expectedBytes = expectedHash.getBytes(StandardCharsets.US_ASCII);
        byte[] storedBytes = storedHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        boolean matched = MessageDigest.isEqual(expectedBytes, storedBytes);
        return matched
                ? PasswordVerificationResult.matched(true)
                : PasswordVerificationResult.failed();
    }

    private void validateRawPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        int passwordBytes = rawPassword.getBytes(StandardCharsets.UTF_8).length;
        if (passwordBytes > BCRYPT_MAX_BYTES) {
            throw new IllegalArgumentException("密码长度不能超过 72 个 UTF-8 字节");
        }
    }
}
