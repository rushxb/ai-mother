package com.rush.rushaicodemother.service.aimodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.config.AiModelSecretProperties;
import com.rush.rushaicodemother.model.dto.aimodel.AiModelAddRequest;
import com.rush.rushaicodemother.model.dto.aimodel.AiModelUpdateRequest;
import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.testsupport.AiModelSecretTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

class AiModelCredentialRedactionTest {

    private static final String PLAINTEXT = "provider-secret-value";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void managementRequestsMustAcceptButNeverSerializeOrPrintApiKeys() throws Exception {
        AiModelAddRequest add = new AiModelAddRequest();
        add.setApiKey(PLAINTEXT);
        AiModelUpdateRequest update = new AiModelUpdateRequest();
        update.setApiKey(PLAINTEXT);

        assertFalse(objectMapper.writeValueAsString(add).contains(PLAINTEXT));
        assertFalse(objectMapper.writeValueAsString(update).contains(PLAINTEXT));
        assertFalse(add.toString().contains(PLAINTEXT));
        assertFalse(update.toString().contains(PLAINTEXT));
    }

    @Test
    void domainAndRuntimeObjectsMustRedactProtectedSecretMetadata() {
        AiModelProtectedSecret secret = AiModelSecretTestFixtures.protect(PLAINTEXT);
        AiModelConfiguration configuration = AiModelConfiguration.builder()
                .secretRef(secret.reference())
                .secretFingerprint(secret.fingerprint())
                .secretKeyId(secret.keyId())
                .build();
        AiModel entity = AiModel.builder()
                .secretRef(secret.reference())
                .secretFingerprint(secret.fingerprint())
                .secretKeyId(secret.keyId())
                .build();
        AiModelRuntimeConfiguration runtime = new AiModelRuntimeConfiguration(
                "custom", "model", "chat", "https://models.example.com/v1",
                secret.reference(), secret.fingerprint(), secret.keyId(), 4096, 0.7, false);

        assertRedacted(configuration.toString(), secret);
        assertRedacted(entity.toString(), secret);
        assertRedacted(runtime.toString(), secret);
    }

    @Test
    void commandsAndConfigurationPropertiesMustRedactCredentialMaterial() {
        AiModelManagementService.CreateCommand create = new AiModelManagementService.CreateCommand(
                null, null, null, null, null, PLAINTEXT,
                null, null, null, null, null, null, null, null);
        AiModelManagementService.UpdateCommand update = new AiModelManagementService.UpdateCommand(
                7L, null, null, null, null, null, PLAINTEXT,
                null, null, null, null, null, null, null, null);
        AiModelSecretProperties properties = new AiModelSecretProperties();
        properties.setActiveKey("sensitive-active-key");
        properties.setFingerprintKey("sensitive-fingerprint-key");
        properties.setPreviousKeys(Map.of("old", "sensitive-previous-key"));

        assertFalse(create.toString().contains(PLAINTEXT));
        assertFalse(update.toString().contains(PLAINTEXT));
        assertFalse(properties.toString().contains("sensitive-active-key"));
        assertFalse(properties.toString().contains("sensitive-fingerprint-key"));
        assertFalse(properties.toString().contains("sensitive-previous-key"));
    }

    private void assertRedacted(String rendered, AiModelProtectedSecret secret) {
        assertFalse(rendered.contains(secret.reference()));
        assertFalse(rendered.contains(secret.fingerprint()));
        assertFalse(rendered.contains(PLAINTEXT));
    }
}
