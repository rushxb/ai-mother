package com.rush.rushaicodemother.service.aimodel;

import java.util.List;

/** Optimistic persistence seam for one-time migration of legacy AI provider credentials. */
public interface AiModelSecretMigrationRepository {

    List<AiModelSecretMigrationRecord> findBatchAfter(long afterId, int batchSize);

    AiModelSecretMigrationRecord findById(long modelId);

    int replaceIfCurrent(long modelId,
                         String expectedLegacySecretSha256,
                         AiModelProtectedSecret protectedSecret);

    int clearDeleted(long modelId);
}
