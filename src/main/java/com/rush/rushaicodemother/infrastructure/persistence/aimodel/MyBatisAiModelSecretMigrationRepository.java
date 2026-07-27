package com.rush.rushaicodemother.infrastructure.persistence.aimodel;

import com.rush.rushaicodemother.mapper.AiModelMapper;
import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.service.aimodel.AiModelProtectedSecret;
import com.rush.rushaicodemother.service.aimodel.AiModelSecretMigrationRecord;
import com.rush.rushaicodemother.service.aimodel.AiModelSecretMigrationRepository;
import com.rush.rushaicodemother.service.release.AiReleaseCoordinationLock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** MyBatis 适配器，用于乐观、多节点安全的 AI 模型秘密迁移。 */
@Repository
@RequiredArgsConstructor
public class MyBatisAiModelSecretMigrationRepository implements AiModelSecretMigrationRepository {

    private final AiModelMapper mapper;
    private final AiReleaseCoordinationLock coordinationLock;

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
    @Transactional(rollbackFor = Exception.class)
    public int replaceIfCurrent(long modelId,
                                String expectedLegacySecretSha256,
                                AiModelProtectedSecret protectedSecret) {
        coordinationLock.acquire();
        return mapper.replaceStoredSecretIfCurrent(
                modelId,
                expectedLegacySecretSha256,
                protectedSecret.reference(),
                protectedSecret.fingerprint(),
                protectedSecret.keyId()
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int clearDeleted(long modelId) {
        coordinationLock.acquire();
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
