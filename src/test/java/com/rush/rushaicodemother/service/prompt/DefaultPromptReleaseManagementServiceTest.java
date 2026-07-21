package com.rush.rushaicodemother.service.prompt;

import com.rush.rushaicodemother.ai.prompt.PromptCatalog;
import com.rush.rushaicodemother.ai.prompt.PromptCatalogSnapshot;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseAction;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseCapabilities;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseConflictException;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseHistoryEntry;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseMutation;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRecord;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRepository;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRuntime;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseState;
import com.rush.rushaicodemother.config.AiPromptCatalogProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.monitor.PromptReleaseMetricsCollector;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubject;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationReleaseEvidenceVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultPromptReleaseManagementServiceTest {

    private static final String EVIDENCE_ID = "550e8400-e29b-41d4-a716-446655440000";

    private PromptCatalog promptCatalog;
    private PromptReleaseRuntime runtime;
    private PromptReleaseRepository repository;
    private PromptReleaseRefreshService refreshService;
    private PromptReleaseCandidateFingerprintService candidateFingerprintService;
    private GenerationReleaseEvidenceVerifier evidenceVerifier;
    private DefaultPromptReleaseManagementService service;

    @BeforeEach
    void setUp() {
        promptCatalog = mock(PromptCatalog.class);
        runtime = mock(PromptReleaseRuntime.class);
        repository = mock(PromptReleaseRepository.class);
        refreshService = mock(PromptReleaseRefreshService.class);
        candidateFingerprintService = mock(PromptReleaseCandidateFingerprintService.class);
        evidenceVerifier = mock(GenerationReleaseEvidenceVerifier.class);
        when(runtime.capabilities()).thenReturn(new PromptReleaseCapabilities(Map.of(
                "test-prompt", Map.of("v1", hash('1'), "v2", hash('2'))
        )));
        when(candidateFingerprintService.fingerprint(any(), any())).thenReturn(hash('c'));
        AiPromptCatalogProperties properties = new AiPromptCatalogProperties();
        properties.getRuntimeReleases().setEnabled(true);
        service = new DefaultPromptReleaseManagementService(
                properties,
                promptCatalog,
                runtime,
                repository,
                refreshService,
                PromptReleaseMetricsCollector.noOp(),
                candidateFingerprintService,
                evidenceVerifier
        );
    }

    @Test
    void publishMustValidateArtifactVersionNormalizeAuditAndRefreshLocally() {
        when(repository.publish(any())).thenReturn(record(4L, new PromptReleaseSpec("v1", "v2", 15)));
        when(refreshService.refreshNow()).thenReturn(PromptReleaseRefreshResult.ACTIVATED);
        when(runtime.activeRevision()).thenReturn(4L);
        when(promptCatalog.bundleId()).thenReturn("bundle-4");

        var result = service.publish(new PromptReleaseManagementService.PublishCommand(
                "test-prompt", "v1", "v2", 15, 0L, "  canary\n release  ", EVIDENCE_ID
        ), 9L);

        assertEquals(4L, result.durableRevision());
        assertTrue(result.appliedLocally());
        ArgumentCaptor<PromptReleaseMutation> captor = ArgumentCaptor.forClass(PromptReleaseMutation.class);
        InOrder releaseOrder = inOrder(evidenceVerifier, repository);
        releaseOrder.verify(evidenceVerifier).requirePassed(
                EVIDENCE_ID,
                GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE,
                "test-prompt",
                hash('c')
        );
        releaseOrder.verify(repository).publish(captor.capture());
        assertEquals("canary release", captor.getValue().changeNote());
        assertEquals(PromptReleaseAction.PUBLISH, captor.getValue().action());
        assertEquals(15, captor.getValue().release().canaryPercentage());
        assertEquals(EVIDENCE_ID, captor.getValue().evidenceId());
        verify(refreshService).refreshNow();
    }

    @Test
    void unknownVersionMustFailBeforePersistence() {
        BusinessException exception = assertThrows(BusinessException.class, () -> service.publish(
                new PromptReleaseManagementService.PublishCommand(
                        "test-prompt", "v3", "", 0, 0L, "unknown version", EVIDENCE_ID
                ),
                9L
        ));

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        verify(repository, never()).publish(any());
    }

    @Test
    void rejectedEvidenceMustFailBeforeReleasePersistence() {
        when(evidenceVerifier.requirePassed(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "benchmark rejected"));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.publish(
                new PromptReleaseManagementService.PublishCommand(
                        "test-prompt", "v1", "", 0, 0L, "rejected release", EVIDENCE_ID
                ),
                9L
        ));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        verify(repository, never()).publish(any());
        verify(refreshService, never()).refreshNow();
    }

    @Test
    void rollbackMustRepublishHistoricalPointersAsNewAuditedRevision() {
        PromptReleaseHistoryEntry target = new PromptReleaseHistoryEntry(
                "test-prompt",
                new PromptReleaseSpec("v1", "", 0),
                2L,
                PromptReleaseAction.PUBLISH,
                null,
                7L,
                "known good",
                Instant.parse("2026-07-17T00:00:00Z")
        );
        when(repository.findHistory("test-prompt", 2L)).thenReturn(Optional.of(target));
        when(repository.publish(any())).thenReturn(record(5L, target.release()));
        when(runtime.activeRevision()).thenReturn(5L);
        when(promptCatalog.bundleId()).thenReturn("bundle-5");

        var result = service.rollback(new PromptReleaseManagementService.RollbackCommand(
                "test-prompt", 2L, 4L, "restore known good"
        ), 9L);

        assertEquals(5L, result.durableRevision());
        ArgumentCaptor<PromptReleaseMutation> captor = ArgumentCaptor.forClass(PromptReleaseMutation.class);
        verify(repository).publish(captor.capture());
        assertEquals(PromptReleaseAction.ROLLBACK, captor.getValue().action());
        assertEquals(2L, captor.getValue().sourceRevision());
    }

    @Test
    void optimisticConflictMustReturnBoundedBusinessError() {
        when(repository.publish(any())).thenThrow(new PromptReleaseConflictException(2L, 3L));

        BusinessException exception = assertThrows(BusinessException.class, () -> service.publish(
                new PromptReleaseManagementService.PublishCommand(
                        "test-prompt", "v1", "", 0, 2L, "stale page", EVIDENCE_ID
                ),
                9L
        ));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        assertFalse(exception.getMessage().contains("2"));
        assertFalse(exception.getMessage().contains("3"));
    }

    @Test
    void overviewMustExposeHashesAndRevisionWithoutPromptContent() {
        PromptReleaseRecord durable = record(3L, new PromptReleaseSpec("v1", "", 0));
        when(repository.loadCurrent()).thenReturn(new PromptReleaseState(
                3L, Map.of("test-prompt", durable)));
        when(promptCatalog.snapshot()).thenReturn(new PromptCatalogSnapshot(
                "bundle-3",
                Map.of("test-prompt", new PromptCatalogSnapshot.PromptRelease(
                        "v1", hash('1'), "", "", 0
                ))
        ));
        when(runtime.activeRevision()).thenReturn(3L);

        var overview = service.getOverview();

        assertEquals("bundle-3", overview.activeBundleId());
        assertEquals(3L, overview.durableBundleRevision());
        assertEquals(2, overview.releases().getFirst().availableVersions().size());
        assertEquals(hash('1'), overview.releases().getFirst().stableContentHash());
    }

    private PromptReleaseRecord record(long revision, PromptReleaseSpec release) {
        return new PromptReleaseRecord(
                "test-prompt",
                release,
                revision,
                9L,
                "release note",
                Instant.parse("2026-07-17T00:00:00Z")
        );
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
