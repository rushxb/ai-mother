package com.rush.rushaicodemother.security.password;

/**
 * 密码哈希与校验边界。
 * 业务层只依赖该接口，避免直接绑定具体算法，便于后续调整哈希参数或迁移算法。
 */
public interface PasswordHashService {

    /**
     * 使用当前安全算法生成不可逆密码哈希。
     */
    String hash(String rawPassword);

    /**
     * 校验原始密码，并告知调用方是否需要升级数据库中的旧哈希。
     */
    PasswordVerificationResult verify(String rawPassword, String storedHash);
}
