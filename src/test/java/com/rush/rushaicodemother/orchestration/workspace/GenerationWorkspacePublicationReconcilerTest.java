package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.config.ArtifactLifecycleProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationWorkspacePublicationReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-07-20T07:00:00Z");

    @Test
    void visibleCrashWindowMustBeRolledForwardToCommittedMetadata() {
        GenerationWorkspacePublicationJournalRepository journal =
                mock(GenerationWorkspacePublicationJournalRepository.class);
        GenerationWorkspacePublicationService publicationService =
                mock(GenerationWorkspacePublicationService.class);
        GenerationWorkspacePublicationMetadataService metadataService =
                mock(GenerationWorkspacePublicationMetadataService.class);
        ArtifactLifecycleProperties properties = new ArtifactLifecycleProperties();
        GenerationWorkspacePublicationJournalEntry entry = entry(
                GenerationWorkspacePublicationJournalStatus.FILESYSTEM_ACTIVATED);
        when(journal.claimPending(
                NOW,
                properties.getPublicationReconciliationBatchSize(),
                properties.getPublicationReconciliationMaxAttempts(),
                properties.getPublicationReconciliationRetryDelay()))
                .thenReturn(List.of(entry));
        when(publicationService.reconcile(entry, metadataService))
                .thenReturn(GenerationWorkspacePublicationService.ReconciliationOutcome.COMMITTED);
        GenerationWorkspacePublicationReconciler reconciler =
                new GenerationWorkspacePublicationReconciler(
                        journal,
                        publicationService,
                        metadataService,
                        properties,
                        Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(1, reconciler.reconcileBatch());
        verify(publicationService).reconcile(entry, metadataService);
    }

    @Test
    void unresolvedPreparedIntentMustRemainDurableForOwningTaskRetry() {
        GenerationWorkspacePublicationJournalRepository journal =
                mock(GenerationWorkspacePublicationJournalRepository.class);
        GenerationWorkspacePublicationService publicationService =
                mock(GenerationWorkspacePublicationService.class);
        GenerationWorkspacePublicationMetadataService metadataService =
                mock(GenerationWorkspacePublicationMetadataService.class);
        ArtifactLifecycleProperties properties = new ArtifactLifecycleProperties();
        GenerationWorkspacePublicationJournalEntry entry = entry(
                GenerationWorkspacePublicationJournalStatus.PREPARED);
        when(journal.claimPending(any(), anyInt(), anyInt(), any()))
                .thenReturn(List.of(entry));
        when(publicationService.reconcile(entry, metadataService))
                .thenReturn(GenerationWorkspacePublicationService.ReconciliationOutcome.PENDING_TASK_RETRY);
        GenerationWorkspacePublicationReconciler reconciler =
                new GenerationWorkspacePublicationReconciler(
                        journal,
                        publicationService,
                        metadataService,
                        properties,
                        Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(0, reconciler.reconcileBatch());
        verify(journal).recordReconciliationFailure(
                entry.pointer(), "publication awaits owning task retry", NOW);
    }

    private GenerationWorkspacePublicationJournalEntry entry(
            GenerationWorkspacePublicationJournalStatus status) {
        return new GenerationWorkspacePublicationJournalEntry(
                "task-1",
                11L,
                CodeGenTypeEnum.VUE_PROJECT,
                5L,
                NOW,
                status,
                0,
                1L,
                ""
        );
    }
}
