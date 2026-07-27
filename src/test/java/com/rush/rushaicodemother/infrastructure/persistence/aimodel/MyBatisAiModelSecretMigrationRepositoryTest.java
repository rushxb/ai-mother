package com.rush.rushaicodemother.infrastructure.persistence.aimodel;

import com.rush.rushaicodemother.mapper.AiModelMapper;
import com.rush.rushaicodemother.service.aimodel.AiModelProtectedSecret;
import com.rush.rushaicodemother.service.release.AiReleaseCoordinationLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MyBatisAiModelSecretMigrationRepositoryTest {

    private AiModelMapper mapper;
    private AiReleaseCoordinationLock coordinationLock;
    private MyBatisAiModelSecretMigrationRepository repository;

    @BeforeEach
    void setUp() {
        mapper = mock(AiModelMapper.class);
        coordinationLock = mock(AiReleaseCoordinationLock.class);
        repository = new MyBatisAiModelSecretMigrationRepository(mapper, coordinationLock);
    }

    @Test
    void replacementMustAcquireGlobalLockBeforeChangingModelIdentity() {
        AiModelProtectedSecret secret = new AiModelProtectedSecret(
                "enc:v1:key:nonce:key:nonce:value", "a".repeat(64), "key");
        when(mapper.replaceStoredSecretIfCurrent(
                7L, "b".repeat(64), secret.reference(), secret.fingerprint(), secret.keyId()))
                .thenReturn(1);

        assertEquals(1, repository.replaceIfCurrent(7L, "b".repeat(64), secret));

        InOrder order = inOrder(coordinationLock, mapper);
        order.verify(coordinationLock).acquire();
        order.verify(mapper).replaceStoredSecretIfCurrent(
                7L, "b".repeat(64), secret.reference(), secret.fingerprint(), secret.keyId());
    }

    @Test
    void deletedSecretCleanupMustAcquireGlobalLockBeforeMutation() {
        when(mapper.clearDeletedStoredSecret(9L)).thenReturn(1);

        assertEquals(1, repository.clearDeleted(9L));

        InOrder order = inOrder(coordinationLock, mapper);
        order.verify(coordinationLock).acquire();
        order.verify(mapper).clearDeletedStoredSecret(9L);
    }
}
