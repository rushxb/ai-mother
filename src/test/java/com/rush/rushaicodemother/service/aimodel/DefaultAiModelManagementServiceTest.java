package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.event.AiModelConfigChangedEvent;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidencePayload;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceProtocol;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceRecord;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceSubject;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationReleaseEvidenceVerifier;
import com.rush.rushaicodemother.service.release.AiReleaseAuditService;
import com.rush.rushaicodemother.service.release.AiReleaseCoordinationLock;
import com.rush.rushaicodemother.testsupport.AiModelSecretTestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;

import static com.rush.rushaicodemother.testsupport.AiModelOutboundSecurityTestFixtures.publicInternetPolicy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultAiModelManagementServiceTest {

    private static final String EVIDENCE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String CANDIDATE_FINGERPRINT = "c".repeat(64);

    private AiModelPersistenceService persistenceService;
    private ApplicationEventPublisher eventPublisher;
    private GenerationReleaseEvidenceVerifier evidenceVerifier;
    private AiReleaseAuditService releaseAuditService;
    private AiReleaseCoordinationLock coordinationLock;
    private AiModelSecretService secretService;
    private DefaultAiModelManagementService service;

    @BeforeEach
    void setUp() {
        persistenceService = mock(AiModelPersistenceService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        evidenceVerifier = mock(GenerationReleaseEvidenceVerifier.class);
        releaseAuditService = mock(AiReleaseAuditService.class);
        coordinationLock = mock(AiReleaseCoordinationLock.class);
        secretService = AiModelSecretTestFixtures.service();
        service = new DefaultAiModelManagementService(
                persistenceService,
                new AiModelConfigurationAssembler(
                        secretService, publicInternetPolicy()),
                new AiModelConfigurationPolicy(
                        secretService, publicInternetPolicy()),
                new AiModelViewAssembler(),
                mock(AiModelConnectionTester.class),
                eventPublisher,
                evidenceVerifier,
                releaseAuditService,
                coordinationLock
        );
    }

    @Test
    void createMustPersistDisabledDraftAndPublishEvent() {
        when(persistenceService.insert(any())).thenReturn(101L);

        long modelId = service.createModel(createCommand(), 9L);

        assertEquals(101L, modelId);
        verify(persistenceService).existsActiveIdentity("custom", "local-model");
        ArgumentCaptor<AiModelConfiguration> configurationCaptor =
                ArgumentCaptor.forClass(AiModelConfiguration.class);
        verify(persistenceService).insert(configurationCaptor.capture());
        assertEquals("https://8.8.8.8/v1", configurationCaptor.getValue().getBaseUrl());
        assertEquals(0, configurationCaptor.getValue().getIsEnabled());
        verify(eventPublisher).publishEvent(any(AiModelConfigChangedEvent.class));
    }

    @Test
    void enabledCreateMustBeRejectedBeforePersistence() {
        AiModelManagementService.CreateCommand command = new AiModelManagementService.CreateCommand(
                "Local Model", "custom", "local-model", null,
                "https://8.8.8.8", "secret", 4096, 0.7,
                1, "chat", 0, 0, null, "openai_chat_completions"
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.createModel(command, 9L)
        );

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        verify(persistenceService, never()).insert(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void blankApiKeyUpdateMustPreserveExistingSecret() {
        when(persistenceService.lockActiveById(7L)).thenReturn(existing());
        AiModelManagementService.UpdateCommand command = new AiModelManagementService.UpdateCommand(
                7L, "Updated", null, null, null, null, "   ",
                null, null, null, null, null, null, null, null
        );

        service.updateModel(command);

        ArgumentCaptor<AiModelConfiguration> configurationCaptor =
                ArgumentCaptor.forClass(AiModelConfiguration.class);
        verify(persistenceService).update(configurationCaptor.capture());
        assertEquals(existing().getSecretFingerprint(),
                configurationCaptor.getValue().getSecretFingerprint());
        assertEquals("existing-secret",
                secretService.resolve(
                        configurationCaptor.getValue().getSecretRef(),
                        configurationCaptor.getValue().getSecretFingerprint()));
        assertEquals("Updated", configurationCaptor.getValue().getModelName());
    }

    @Test
    void destinationHostChangeWithoutNewApiKeyMustStopBeforePersistence() {
        when(persistenceService.lockActiveById(7L)).thenReturn(existing());
        AiModelManagementService.UpdateCommand command =
                new AiModelManagementService.UpdateCommand(
                        7L, null, null, null, null,
                        "https://1.1.1.1/v1", "   ",
                        null, null, null, null, null, null, null, null
                );

        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.updateModel(command));

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        verify(persistenceService, never()).update(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void missingModelLookupMustReturnNotFoundInsteadOfNullView() {
        BusinessException exception = assertThrows(BusinessException.class, () -> service.getModelById(999L));

        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), exception.getCode());
    }

    @Test
    void duplicateCreateMustStopBeforeStateMutation() {
        when(persistenceService.existsActiveIdentity("custom", "local-model")).thenReturn(true);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.createModel(createCommand(), 9L)
        );

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        verify(persistenceService, never()).insert(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void updateMustNotEnableDisabledModelWithoutEvidenceGate() {
        when(persistenceService.lockActiveById(7L)).thenReturn(existing());
        AiModelManagementService.UpdateCommand command = new AiModelManagementService.UpdateCommand(
                7L, null, null, null, null, null, null,
                null, null, 1, null, null, null, null, null
        );

        BusinessException exception = assertThrows(BusinessException.class, () -> service.updateModel(command));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        verify(persistenceService, never()).update(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void onlineModelMustBeDisabledBeforeConfigurationChanges() {
        when(persistenceService.lockActiveById(7L))
                .thenReturn(existing().toBuilder().isEnabled(1).build());
        AiModelManagementService.UpdateCommand command = new AiModelManagementService.UpdateCommand(
                7L, "Updated", null, null, null, null, null,
                null, null, null, null, null, null, null, null
        );

        BusinessException exception = assertThrows(BusinessException.class, () -> service.updateModel(command));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        verify(persistenceService, never()).update(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void enablingModelMustVerifyExactCandidateBeforePersistenceAndAudit() {
        GenerationBenchmarkEvidenceRecord evidence = evidence(CANDIDATE_FINGERPRINT);
        when(persistenceService.lockActiveById(7L)).thenReturn(existing());
        when(evidenceVerifier.requirePassed(
                EVIDENCE_ID,
                new GenerationBenchmarkEvidenceCandidate.AiModelEnable(7L)
        )).thenReturn(evidence);

        var result = service.toggleModelEnabled(7L, EVIDENCE_ID, 9L);

        assertEquals(1, result.getIsEnabled());
        ArgumentCaptor<AiModelConfiguration> configurationCaptor =
                ArgumentCaptor.forClass(AiModelConfiguration.class);
        InOrder releaseOrder = inOrder(
                coordinationLock,
                persistenceService,
                evidenceVerifier,
                releaseAuditService
        );
        releaseOrder.verify(coordinationLock).acquire();
        releaseOrder.verify(persistenceService).lockActiveById(7L);
        releaseOrder.verify(evidenceVerifier).requirePassed(
                EVIDENCE_ID,
                new GenerationBenchmarkEvidenceCandidate.AiModelEnable(7L)
        );
        releaseOrder.verify(persistenceService).update(configurationCaptor.capture());
        assertEquals(1, configurationCaptor.getValue().getIsEnabled());
        releaseOrder.verify(releaseAuditService).recordModelEnable(evidence, 9L, 7L);
    }

    @Test
    void rejectedEvidenceMustPreventEnableMutationAndAudit() {
        when(persistenceService.lockActiveById(7L)).thenReturn(existing());
        when(evidenceVerifier.requirePassed(any(), any()))
                .thenThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "evidence rejected"));

        assertThrows(BusinessException.class,
                () -> service.toggleModelEnabled(7L, EVIDENCE_ID, 9L));

        verify(persistenceService, never()).update(any());
        verifyNoInteractions(releaseAuditService);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void disablingModelMustRemainImmediateWithoutReleaseEvidence() {
        when(persistenceService.lockActiveById(7L)).thenReturn(existing().toBuilder().isEnabled(1).build());

        var result = service.toggleModelEnabled(7L, null, 9L);

        assertEquals(0, result.getIsEnabled());
        verify(coordinationLock).acquire();
        verify(persistenceService).update(any());
        verifyNoInteractions(evidenceVerifier, releaseAuditService);
        verify(eventPublisher).publishEvent(any(AiModelConfigChangedEvent.class));
    }

    @Test
    void deletionMustAcquireGlobalLockBeforeTheModelRowLock() {
        when(persistenceService.lockActiveById(7L)).thenReturn(existing());

        service.deleteModel(7L);

        InOrder order = inOrder(coordinationLock, persistenceService);
        order.verify(coordinationLock).acquire();
        order.verify(persistenceService).lockActiveById(7L);
        order.verify(persistenceService).logicallyDelete(7L);
    }

    private AiModelManagementService.CreateCommand createCommand() {
        return new AiModelManagementService.CreateCommand(
                "Local Model", "custom", "local-model", null,
                "https://8.8.8.8", "secret", 4096, 0.7,
                0, "chat", 0, 0, null, "openai_chat_completions"
        );
    }

    private AiModelConfiguration existing() {
        AiModelProtectedSecret secret = AiModelSecretTestFixtures.protect("existing-secret");
        return AiModelConfiguration.builder()
                .id(7L)
                .modelName("Existing")
                .provider("custom")
                .modelId("existing-model")
                .baseUrl("https://8.8.8.8/v1")
                .secretRef(secret.reference())
                .secretFingerprint(secret.fingerprint())
                .secretKeyId(secret.keyId())
                .maxTokens(4096)
                .temperature(0.7)
                .isEnabled(0)
                .modelType("chat")
                .supportsThinking(0)
                .sortOrder(0)
                .userId(10L)
                .build();
    }

    private GenerationBenchmarkEvidenceRecord evidence(String candidateFingerprint) {
        Instant evaluatedAt = Instant.parse("2026-07-18T00:00:00Z");
        return new GenerationBenchmarkEvidenceRecord(
                EVIDENCE_ID,
                new GenerationBenchmarkEvidencePayload(
                        GenerationBenchmarkEvidenceProtocol.CURRENT_SIGNATURE_VERSION,
                        GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE,
                        "7",
                        candidateFingerprint,
                        1L,
                        "dataset",
                        "grader",
                        "runtime",
                        "commit",
                        "model",
                        "prompt",
                        "report",
                        evaluatedAt,
                        evaluatedAt.plusSeconds(3600)
                ),
                "{}",
                true,
                List.of(),
                "signature",
                evaluatedAt
        );
    }
}
