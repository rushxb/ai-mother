package com.rush.rushaicodemother.security.password;

/**
 * 密码校验结果。
 *
 * @param matched         密码是否匹配
 * @param upgradeRequired 是否应使用当前算法重新生成并保存哈希
 */
public record PasswordVerificationResult(boolean matched, boolean upgradeRequired) {

    public static PasswordVerificationResult failed() {
        return new PasswordVerificationResult(false, false);
    }

    public static PasswordVerificationResult matched(boolean upgradeRequired) {
        return new PasswordVerificationResult(true, upgradeRequired);
    }
}
