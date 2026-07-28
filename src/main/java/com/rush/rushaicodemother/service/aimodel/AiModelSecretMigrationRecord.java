package com.rush.rushaicodemother.service.aimodel;

/** 在删除旧的纯文本提供者凭据时使用最小持久性投影。 */
public record AiModelSecretMigrationRecord(
        long modelId,
        String secretRef,
        String secretFingerprint,
        String secretKeyId,
        boolean deleted
) {

    /** 创建 AI 模型密钥{@code Migration}记录实例并完成必要的依赖和初始状态设置。 */
    public AiModelSecretMigrationRecord {
        if (modelId <= 0) {
            throw new IllegalArgumentException("AI model secret migration record id must be positive");
        }
    }

    @Override
    public String toString() {
        return "AiModelSecretMigrationRecord[modelId=" + modelId
                + ", secretRef=<redacted>, secretFingerprint=<redacted>, secretKeyId="
                + secretKeyId + ", deleted=" + deleted + ']';
    }
}
