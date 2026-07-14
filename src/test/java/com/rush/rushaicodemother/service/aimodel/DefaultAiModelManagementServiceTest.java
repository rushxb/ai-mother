package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.event.AiModelConfigChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultAiModelManagementServiceTest {

    private AiModelPersistenceService persistenceService;
    private ApplicationEventPublisher eventPublisher;
    private DefaultAiModelManagementService service;

    @BeforeEach
    void setUp() {
        persistenceService = mock(AiModelPersistenceService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new DefaultAiModelManagementService(
                persistenceService,
                new AiModelConfigurationAssembler(),
                new AiModelConfigurationPolicy(),
                new AiModelViewAssembler(),
                mock(AiModelConnectionTester.class),
                eventPublisher
        );
    }

    @Test
    void enabledCreateMustDisableSameTypeThenInsertAndPublishEvent() {
        when(persistenceService.insert(any())).thenReturn(101L);

        long modelId = service.createModel(createCommand(), 9L);

        assertEquals(101L, modelId);
        verify(persistenceService).existsActiveIdentity("custom", "local-model");
        verify(persistenceService).disableOtherEnabledModels("chat", null);
        ArgumentCaptor<AiModelConfiguration> configurationCaptor =
                ArgumentCaptor.forClass(AiModelConfiguration.class);
        verify(persistenceService).insert(configurationCaptor.capture());
        assertEquals("http://localhost:11434/v1", configurationCaptor.getValue().getBaseUrl());
        verify(eventPublisher).publishEvent(any(AiModelConfigChangedEvent.class));
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
        assertEquals("existing-secret", configurationCaptor.getValue().getApiKey());
        assertEquals("Updated", configurationCaptor.getValue().getModelName());
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
        verify(persistenceService, never()).disableOtherEnabledModels(any(), any());
        verify(persistenceService, never()).insert(any());
        verifyNoInteractions(eventPublisher);
    }

    private AiModelManagementService.CreateCommand createCommand() {
        return new AiModelManagementService.CreateCommand(
                "Local Model", "custom", "local-model", null,
                "http://localhost:11434", "secret", 4096, 0.7,
                1, "chat", 0, 0, null, "openai_chat_completions"
        );
    }

    private AiModelConfiguration existing() {
        return AiModelConfiguration.builder()
                .id(7L)
                .modelName("Existing")
                .provider("custom")
                .modelId("existing-model")
                .baseUrl("http://localhost:11434/v1")
                .apiKey("existing-secret")
                .maxTokens(4096)
                .temperature(0.7)
                .isEnabled(0)
                .modelType("chat")
                .supportsThinking(0)
                .sortOrder(0)
                .userId(10L)
                .build();
    }
}
