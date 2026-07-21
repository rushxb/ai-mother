package com.rush.rushaicodemother.infrastructure.persistence.aimodel;

import com.rush.rushaicodemother.mapper.AiModelMapper;
import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.service.aimodel.AiModelProtectedSecret;
import com.rush.rushaicodemother.service.aimodel.AiModelSecretMigrationRecord;
import com.rush.rushaicodemother.service.aimodel.AiModelSecretMigrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** MyBatis adapter for optimistic, multi-node-safe AI model secret migration. */
@Repository
@RequiredArgsConstructor
public class MyBatisAiModelSecretMigrationRepository implements AiModelSecretMigrationRepository {

    private final AiModelMapper mapper;

    @Override
    public List<AiModelSecretMigrationRecord> findBatchAfter(long afterId, int batchSize) {
        return mapper.selectSecretMigrationBatch(afterId, batchSize).stream()
                .map(this::toRecord)
                .toList();
    }

    @Override
    public AiModelSecretMigrationRecord findById(long modelId) {
        return toRecord(mapper.selectStoredSecretById(modelId));
    }

    @Override
    public int replaceIfCurrent(long modelId,
                                String expectedLegacySecretSha256,
                                AiModelProtectedSecret protectedSecret) {
        return mapper.replaceStoredSecretIfCurrent(
                modelId,
                expectedLegacySecretSha256,
                protectedSecret.reference(),
                protectedSecret.fingerprint(),
                protectedSecret.keyId()
        );
    }

    @Override
    public int clearDeleted(long modelId) {
        return mapper.clearDeletedStoredSecret(modelId);
    }

    private AiModelSecretMigrationRecord toRecord(AiModel entity) {
        if (entity == null) {
            return null;
        }
        return new AiModelSecretMigrationRecord(
                entity.getId(),
                entity.getSecretRef(),
                entity.getSecretFingerprint(),
                entity.getSecretKeyId(),
                Integer.valueOf(1).equals(entity.getIsDelete())
        );
    }
}
