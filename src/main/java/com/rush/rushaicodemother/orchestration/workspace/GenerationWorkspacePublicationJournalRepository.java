package com.rush.rushaicodemother.orchestration.workspace;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Relational source of truth for the filesystem/metadata publication saga. */
public interface GenerationWorkspacePublicationJournalRepository {

    GenerationWorkspacePublicationJournalEntry prepare(
            GenerationWorkspacePublicationPointer candidate,
            Instant preparedAt);

    void markFilesystemActivated(GenerationWorkspacePublicationPointer pointer, Instant activatedAt);

    void markCommitted(GenerationWorkspacePublicationPointer pointer, Instant committedAt);

    void markRolledBack(GenerationWorkspacePublicationPointer pointer,
                        String error,
                        Instant rolledBackAt);

    void markRollbackRequired(GenerationWorkspacePublicationPointer pointer,
                              String error,
                              Instant failedAt);

    void markSuperseded(GenerationWorkspacePublicationPointer pointer,
                        String reason,
                        Instant supersededAt);

    List<GenerationWorkspacePublicationJournalEntry> claimPending(
            Instant now,
            int limit,
            int maxAttempts,
            Duration retryDelay);

    void recordReconciliationFailure(GenerationWorkspacePublicationPointer pointer,
                                     String error,
                                     Instant failedAt);
}
