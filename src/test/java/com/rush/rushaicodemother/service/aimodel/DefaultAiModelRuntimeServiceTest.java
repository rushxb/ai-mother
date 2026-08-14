package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.testsupport.AiModelSecretTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.rush.rushaicodemother.testsupport.AiModelOutboundSecurityTestFixtures.publicInternetPolicy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAiModelRuntimeServiceTest {

    private final AiModelEnabledConfigurationSource configurationSource =
            mock(AiModelEnabledConfigurationSource.class);
    private final AiModelCircuitBreaker circuitBreaker = mock(AiModelCircuitBreaker.class);
    private final AiModelSecretService secretService = AiModelSecretTestFixtures.service();
    private final DefaultAiModelRuntimeService service = new DefaultAiModelRuntimeService(
            configurationSource,
            new AiModelConfigurationPolicy(
                    secretService, publicInternetPolicy()),
            circuitBreaker
    );

    @Test
    void mustSkipInvalidEnabledRecordsAndReturnMinimalRuntimeConfiguration() {
        when(configurationSource.findEnabled("chat")).thenReturn(List.of(
                configuration("", "bad"),
                configuration("secret", "good")
        ));
        when(circuitBreaker.isAvailable("custom", "good")).thenReturn(true);

        AiModelRuntimeConfiguration result = service.requireRunnableModelByType("chat");

        assertEquals("good", result.modelId());
        assertEquals("secret", secretService.resolve(
                result.secretRef(), result.secretFingerprint()));
    }

    @Test
    void generationPreflightMustRequireChatAndReasoningModels() {
        when(configurationSource.findEnabled("chat")).thenReturn(List.of(configuration("secret", "chat-model")));
        when(configurationSource.findEnabled("reasoning"))
                .thenReturn(List.of(configuration("secret", "reasoning-model").toBuilder()
                        .modelType("reasoning")
                        .build()));
        when(circuitBreaker.isAvailable("custom", "chat-model")).thenReturn(true);
        when(circuitBreaker.isAvailable("custom", "reasoning-model")).thenReturn(true);

        service.ensureGenerationModelsConfigured();

        verify(configurationSource).findEnabled("chat");
        verify(configurationSource).findEnabled("reasoning");
    }

    @Test
    void missingRunnableModelMustFailWithBusinessException() {
        when(configurationSource.findEnabled("chat")).thenReturn(List.of());

        assertThrows(BusinessException.class, () -> service.requireRunnableModelByType("chat"));
    }

    @Test
    void openPrimaryCircuitMustRouteToNextEnabledModelBySortOrder() {
        when(configurationSource.findEnabled("chat")).thenReturn(List.of(
                configuration("secret", "primary"),
                configuration("backup", "fallback")
        ));
        when(circuitBreaker.isAvailable("custom", "primary")).thenReturn(false);
        when(circuitBreaker.isAvailable("custom", "fallback")).thenReturn(true);

        AiModelRuntimeConfiguration result = service.requireRunnableModelByType("chat");

        assertEquals("fallback", result.modelId());
    }

    @Test
    void runnablePoolMustPreserveConfiguredOrderAndExcludeOpenCircuits() {
        when(configurationSource.findEnabled("chat")).thenReturn(List.of(
                configuration("first-key", "first"),
                configuration("second-key", "open"),
                configuration("third-key", "third")
        ));
        when(circuitBreaker.isAvailable("custom", "first")).thenReturn(true);
        when(circuitBreaker.isAvailable("custom", "open")).thenReturn(false);
        when(circuitBreaker.isAvailable("custom", "third")).thenReturn(true);

        List<AiModelRuntimeConfiguration> result = service.listRunnableModelsByType("chat");

        assertEquals(List.of("first", "third"), result.stream()
                .map(AiModelRuntimeConfiguration::modelId)
                .toList());
    }

    private AiModelConfiguration configuration(String apiKey, String modelId) {
        AiModelConfiguration.AiModelConfigurationBuilder builder = AiModelConfiguration.builder()
                .id(7L)
                .modelName("Model")
                .provider("custom")
                .modelId(modelId)
                .baseUrl("https://8.8.8.8/v1")
                .maxTokens(4096)
                .temperature(0.7)
                .isEnabled(1)
                .modelType("chat")
                .supportsThinking(0)
                .sortOrder(0);
        if (apiKey != null && !apiKey.isBlank()) {
            AiModelProtectedSecret secret = AiModelSecretTestFixtures.protect(apiKey);
            builder.secretRef(secret.reference())
                    .secretFingerprint(secret.fingerprint())
                    .secretKeyId(secret.keyId());
        }
        return builder.build();
    }
}
