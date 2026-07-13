package com.rush.rushaicodemother.security.password;

import org.junit.jupiter.api.Test;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BcryptPasswordHashServiceTest {

    private final BcryptPasswordHashService passwordHashService = new BcryptPasswordHashService();

    @Test
    void shouldHashAndVerifyPasswordWithBcrypt() {
        String rawPassword = "secure-password";

        String hash = passwordHashService.hash(rawPassword);
        PasswordVerificationResult result = passwordHashService.verify(rawPassword, hash);

        assertNotEquals(rawPassword, hash);
        assertTrue(hash.startsWith("$2"));
        assertTrue(result.matched());
        assertFalse(result.upgradeRequired());
    }

    @Test
    void shouldVerifyLegacyMd5AndRequestUpgrade() {
        String rawPassword = "legacy-password";
        String legacyHash = DigestUtils.md5DigestAsHex(
                (rawPassword + "rush").getBytes(StandardCharsets.UTF_8)
        );

        PasswordVerificationResult result = passwordHashService.verify(rawPassword, legacyHash);

        assertTrue(result.matched());
        assertTrue(result.upgradeRequired());
    }

    @Test
    void shouldRejectWrongOrUnknownHashes() {
        assertFalse(passwordHashService.verify("wrong-password", passwordHashService.hash("correct-password")).matched());
        assertFalse(passwordHashService.verify("password", "plain-text-password").matched());
        assertFalse(passwordHashService.verify(null, null).matched());
    }

    @Test
    void shouldRejectPasswordsBeyondBcryptByteLimit() {
        String oversizedPassword = "密".repeat(25);

        assertThrows(IllegalArgumentException.class, () -> passwordHashService.hash(oversizedPassword));
    }
}
