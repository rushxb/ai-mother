package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.testsupport.AiModelSecretTestFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiModelSecretMigrationRunnerTest {

    @Test
    void legacyActiveSecretMustBeEncryptedWithOptimisticReplacement() throws Exception {
        AiModelSecretMigrationRepository repository = mock(AiModelSecretMigrationRepository.class);
        AiModelSecretService secretService = AiModelSecretTestFixtures.service();
        AiModelSecretMigrationRecord legacy =
                new AiModelSecretMigrationRecord(7L, "legacy-plaintext", null, null, false);
        when(repository.findBatchAfter(0L, 100)).thenReturn(List.of(legacy));
        when(repository.replaceIfCurrent(anyLong(), anyString(), any()))
                .thenReturn(1);

        new AiModelSecretMigrationRunner(repository, secretService).run(null);

        ArgumentCaptor<AiModelProtectedSecret> protectedCaptor =
                ArgumentCaptor.forClass(AiModelProtectedSecret.class);
        ArgumentCaptor<String> digestCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).replaceIfCurrent(
                org.mockito.ArgumentMatchers.eq(7L),
                digestCaptor.capture(),
                protectedCaptor.capture()
        );
        assertFalse(digestCaptor.getValue().contains("legacy-plaintext"));
        assertEquals(64, digestCaptor.getValue().length());
        AiModelProtectedSecret stored = protectedCaptor.getValue();
        assertFalse(stored.reference().contains("legacy-plaintext"));
        assertEquals("legacy-plaintext", secretService.resolve(
                stored.reference(), stored.fingerprint()));
    }

    @Test
    void casLoserMustAcceptOnlyAnAlreadyProtectedConcurrentResult() throws Exception {
        AiModelSecretMigrationRepository repository = mock(AiModelSecretMigrationRepository.class);
        AiModelSecretService secretService = AiModelSecretTestFixtures.service();
        AiModelSecretMigrationRecord legacy =
                new AiModelSecretMigrationRecord(7L, "legacy-plaintext", null, null, false);
        AiModelProtectedSecret concurrent = AiModelSecretTestFixtures.protect("rotated-by-peer");
        when(repository.findBatchAfter(0L, 100)).thenReturn(List.of(legacy));
        when(repository.replaceIfCurrent(anyLong(), anyString(), any()))
                .thenReturn(0);
        when(repository.findById(7L)).thenReturn(new AiModelSecretMigrationRecord(
                7L, concurrent.reference(), concurrent.fingerprint(), concurrent.keyId(), false));

        assertDoesNotThrow(() ->
                new AiModelSecretMigrationRunner(repository, secretService).run(null));
    }

    @Test
    void casLoserMustFailClosedWhenPlaintextStillExists() {
        AiModelSecretMigrationRepository repository = mock(AiModelSecretMigrationRepository.class);
        AiModelSecretService secretService = AiModelSecretTestFixtures.service();
        AiModelSecretMigrationRecord legacy =
                new AiModelSecretMigrationRecord(7L, "legacy-plaintext", null, null, false);
        when(repository.findBatchAfter(0L, 100)).thenReturn(List.of(legacy));
        when(repository.replaceIfCurrent(anyLong(), anyString(), any()))
                .thenReturn(0);
        when(repository.findById(7L)).thenReturn(legacy);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> new AiModelSecretMigrationRunner(repository, secretService).run(null)
        );

        assertFalse(exception.getMessage().contains("legacy-plaintext"));
    }

    @Test
    void logicallyDeletedCredentialsMustBeErasedWithoutEncryptionKeys() throws Exception {
        AiModelSecretMigrationRepository repository = mock(AiModelSecretMigrationRepository.class);
        AiModelSecretService secretService = mock(AiModelSecretService.class);
        AiModelSecretMigrationRecord deleted =
                new AiModelSecretMigrationRecord(9L, "legacy-deleted", null, null, true);
        when(repository.findBatchAfter(0L, 100)).thenReturn(List.of(deleted));
        when(repository.clearDeleted(9L)).thenReturn(1);

        new AiModelSecretMigrationRunner(repository, secretService).run(null);

        verify(repository).clearDeleted(9L);
        verifyNoInteractions(secretService);
    }

    @Test
    void protectedReferenceWithMissingMetadataMustBlockStartup() {
        AiModelSecretMigrationRepository repository = mock(AiModelSecretMigrationRepository.class);
        AiModelSecretService secretService = AiModelSecretTestFixtures.service();
        AiModelProtectedSecret secret = AiModelSecretTestFixtures.protect("secret");
        when(repository.findBatchAfter(0L, 100)).thenReturn(List.of(
                new AiModelSecretMigrationRecord(11L, secret.reference(), null, secret.keyId(), false)
        ));

        assertThrows(IllegalStateException.class,
                () -> new AiModelSecretMigrationRunner(repository, secretService).run(null));
    }
}
