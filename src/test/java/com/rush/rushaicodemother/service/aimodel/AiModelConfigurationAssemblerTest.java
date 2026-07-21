package com.rush.rushaicodemother.service.aimodel;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.testsupport.AiModelSecretTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelConfigurationAssemblerTest {

    private final AiModelSecretService secretService = AiModelSecretTestFixtures.service();
    private final AiModelConfigurationAssembler assembler =
            new AiModelConfigurationAssembler(secretService);

    @Test
    void createMustMapExplicitFieldsAndApplyDefaults() {
        AiModelConfiguration configuration = assembler.fromCreateCommand(
                new AiModelManagementService.CreateCommand(
                        "New Model", "custom", "new-model", "description",
                        "http://localhost:11434/v1", "secret", 8192, 0.7,
                        null, "chat", 1, null,
                        "{\"timeoutSeconds\":30}", " openai_chat_completions "
                ),
                9L
        );

        assertEquals("New Model", configuration.getModelName());
        assertTrue(secretService.isProtectedReference(configuration.getSecretRef()));
        assertEquals("secret", secretService.resolve(
                configuration.getSecretRef(), configuration.getSecretFingerprint()));
        assertEquals(0, configuration.getIsEnabled());
        assertEquals(0, configuration.getSortOrder());
        assertEquals(9L, configuration.getUserId());
        JSONObject config = JSONUtil.parseObj(configuration.getConfigJson());
        assertEquals(30, config.getInt("timeoutSeconds"));
        assertEquals("openai_chat_completions", config.getStr("protocol"));
        assertNull(configuration.getId());
        assertNull(configuration.getCreateTime());
    }

    @Test
    void blankApiKeyUpdateMustPreserveSecretAndAuditFields() {
        LocalDateTime createTime = LocalDateTime.of(2026, 7, 13, 10, 0);
        AiModelConfiguration existing = existing().toBuilder().createTime(createTime).build();

        AiModelConfiguration updated = assembler.applyUpdate(existing,
                new AiModelManagementService.UpdateCommand(
                        7L, "Updated", null, null, null, null, "   ",
                        null, null, null, null, null, null, null, null
                ));

        assertEquals("Updated", updated.getModelName());
        assertEquals(existing.getSecretRef(), updated.getSecretRef());
        assertEquals(existing.getSecretFingerprint(), updated.getSecretFingerprint());
        assertEquals(10L, updated.getUserId());
        assertEquals(createTime, updated.getCreateTime());
    }

    @Test
    void protocolOnlyUpdateMustPreserveOtherExtensionSettings() {
        AiModelConfiguration existing = existing().toBuilder()
                .configJson("{\"timeoutSeconds\":45,\"retries\":2}")
                .build();

        AiModelConfiguration updated = assembler.applyUpdate(existing,
                new AiModelManagementService.UpdateCommand(
                        7L, null, null, null, null, null, null,
                        null, null, null, null, null, null, null,
                        "openai_chat_completions"
                ));

        JSONObject config = JSONUtil.parseObj(updated.getConfigJson());
        assertEquals(45, config.getInt("timeoutSeconds"));
        assertEquals(2, config.getInt("retries"));
        assertEquals("openai_chat_completions", config.getStr("protocol"));
    }

    @Test
    void idOnlyUpdateMustBeRejected() {
        AiModelManagementService.UpdateCommand command = new AiModelManagementService.UpdateCommand(
                7L, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null
        );

        assertThrows(BusinessException.class, () -> assembler.applyUpdate(existing(), command));
    }

    private AiModelConfiguration existing() {
        AiModelProtectedSecret secret = AiModelSecretTestFixtures.protect("existing-secret");
        return AiModelConfiguration.builder()
                .id(7L)
                .modelName("Existing")
                .provider("custom")
                .modelId("existing-model")
                .baseUrl("http://localhost:11434/v1")
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
}
