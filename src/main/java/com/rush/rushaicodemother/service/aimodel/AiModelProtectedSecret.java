package com.rush.rushaicodemother.service.aimodel;

/** 保护一个提供商 API 密钥的持久输出。 */
public record AiModelProtectedSecret(
        String reference,
        String fingerprint,
        String keyId
) {
    /** 创建 AI 模型{@code Protected}密钥实例并完成必要的依赖和初始状态设置。 */
    public AiModelProtectedSecret {
        if (reference == null || reference.isBlank()
                || fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")
                || keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("protected AI model secret metadata is invalid");
        }
    }

    @Override
    public String toString() {
        return "AiModelProtectedSecret[reference=<redacted>, fingerprint=<redacted>, keyId="
                + keyId + ']';
    }
}
