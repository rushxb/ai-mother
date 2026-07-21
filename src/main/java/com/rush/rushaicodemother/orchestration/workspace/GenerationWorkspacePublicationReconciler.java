package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.config.ArtifactLifecycleProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Reconciles crash windows between pointer activation and relational metadata commit. */
@Slf4j
@Service
public class GenerationWorkspacePublicationReconciler {

    private final GenerationWorkspacePublicationJournalRepository journalRepository;
    private final GenerationWorkspacePublicationService publicationService;
    private final GenerationWorkspacePublicationMetadataService metadataService;
    private final ArtifactLifecycleProperties properties;
    private final Clock clock;

    public GenerationWorkspacePublicationReconciler(
            GenerationWorkspacePublicationJournalRepository journalRepository,
            GenerationWorkspacePublicationService publicationService,
            GenerationWorkspacePublicationMetadataService metadataService,
            ArtifactLifecycleProperties properties) {
        this(journalRepository, publicationService, metadataService, properties, Clock.systemUTC());
    }

    GenerationWorkspacePublicationReconciler(
            GenerationWorkspacePublicationJournalRepository journalRepository,
            GenerationWorkspacePublicationService publicationService,
            GenerationWorkspacePublicationMetadataService metadataService,
            ArtifactLifecycleProperties properties,
            Clock clock) {
        this.journalRepository = Objects.requireNonNull(journalRepository, "journalRepository");
        this.publicationService = Objects.requireNonNull(publicationService, "publicationService");
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(fixedDelayString =
            "${app.artifact-lifecycle.publication-reconciliation-scan-interval:30s}")
    public void reconcilePending() {
        try {
            reconcileBatch();
        } catch (RuntimeException failure) {
            log.error("Workspace publication reconciliation scan failed: {}",
                    LogExceptionSanitizer.sanitizeMessage(failure));
        }
    }

    int reconcileBatch() {
        Instant now = clock.instant();
        int completed = 0;
        for (GenerationWorkspacePublicationJournalEntry entry : journalRepository.claimPending(
                now,
                properties.getPublicationReconciliationBatchSize(),
                properties.getPublicationReconciliationMaxAttempts(),
                properties.getPublicationReconciliationRetryDelay())) {
            GenerationWorkspacePublicationPointer pointer = entry.pointer();
            try {
                GenerationWorkspacePublicationService.ReconciliationOutcome outcome =
                        publicationService.reconcile(entry, metadataService);
                if (outcome == GenerationWorkspacePublicationService.ReconciliationOutcome.COMMITTED
                        || outcome == GenerationWorkspacePublicationService.ReconciliationOutcome.SUPERSEDED
                        || outcome == GenerationWorkspacePublicationService.ReconciliationOutcome.ROLLED_BACK) {
                    completed++;
                } else {
                    journalRepository.recordReconciliationFailure(
                            pointer, "publication awaits owning task retry", clock.instant());
                }
            } catch (RuntimeException failure) {
                String diagnostic = LogExceptionSanitizer.sanitizeMessage(failure);
                try {
                    journalRepository.recordReconciliationFailure(
                            pointer, diagnostic, clock.instant());
                } catch (RuntimeException recordFailure) {
                    failure.addSuppressed(recordFailure);
                }
                log.warn("Workspace publication reconciliation failed, taskId: {}, epoch: {}, error: {}",
                        pointer.taskId(), pointer.executionEpoch(), diagnostic);
            }
        }
        return completed;
    }
}
