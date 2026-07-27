package com.rush.rushaicodemother.service.aimodel;

import java.util.List;

/** 用于一次性迁移遗留 AI 提供商凭证的乐观持久性接缝。 */
public interface AiModelSecretMigrationRepository {

    List<AiModelSecretMigrationRecord> findBatchAfter(long afterId, int batchSize);

    AiModelSecretMigrationRecord findById(long modelId);

    int replaceIfCurrent(long modelId,
                         String expectedLegacySecretSha256,
                         AiModelProtectedSecret protectedSecret);

    int clearDeleted(long modelId);
}
