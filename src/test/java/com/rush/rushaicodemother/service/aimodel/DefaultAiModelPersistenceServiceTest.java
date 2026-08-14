package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.mapper.AiModelMapper;
import com.rush.rushaicodemother.model.entity.AiModel;
import com.rush.rushaicodemother.testsupport.AiModelSecretTestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAiModelPersistenceServiceTest {

    private final AiModelMapper mapper = mock(AiModelMapper.class);
    private final DefaultAiModelPersistenceService service = new DefaultAiModelPersistenceService(mapper);

    @Test
    void insertMustRequireGeneratedDatabaseIdentity() {
        when(mapper.insertModel(any())).thenAnswer(invocation -> {
            AiModel entity = invocation.getArgument(0);
            entity.setId(101L);
            return 1;
        });

        assertEquals(101L, service.insert(configuration()));
    }

    @Test
    void duplicateKeyMustMapToStableBusinessError() {
        doThrow(new DuplicateKeyException("uk_active_provider_model"))
                .when(mapper).insertModel(any());

        assertThrows(BusinessException.class, () -> service.insert(configuration()));
    }

    @Test
    void pageMustMapOnlyWhitelistedSortColumnAndDirection() {
        when(mapper.countActive(null, null, null, null)).thenReturn(1L);
        when(mapper.selectActivePage(null, null, null, null,
                "updateTime", "ASC", 10, 0L)).thenReturn(List.of(entity()));

        service.pageActive(new AiModelPersistenceService.QueryCriteria(
                1, 10, null, null, null, null, "updateTime", "ascend"
        ));

        verify(mapper).selectActivePage(null, null, null, null,
                "updateTime", "ASC", 10, 0L);
    }

    @Test
    void logicalDeleteMustRequireExactlyOneActiveRow() {
        when(mapper.logicallyDeleteActiveModel(7L)).thenReturn(0);

        assertThrows(BusinessException.class, () -> service.logicallyDelete(7L));
    }

    private AiModelConfiguration configuration() {
        AiModelProtectedSecret secret = AiModelSecretTestFixtures.protect("secret");
        return AiModelConfiguration.builder()
                .modelName("Model")
                .provider("custom")
                .modelId("model-id")
                .baseUrl("https://8.8.8.8/v1")
                .secretRef(secret.reference())
                .secretFingerprint(secret.fingerprint())
                .secretKeyId(secret.keyId())
                .maxTokens(4096)
                .temperature(0.7)
                .isEnabled(1)
                .modelType("chat")
                .supportsThinking(0)
                .sortOrder(0)
                .userId(9L)
                .build();
    }

    private AiModel entity() {
        AiModelProtectedSecret secret = AiModelSecretTestFixtures.protect("secret");
        return AiModel.builder()
                .id(7L)
                .modelName("Model")
                .provider("custom")
                .modelId("model-id")
                .baseUrl("https://8.8.8.8/v1")
                .secretRef(secret.reference())
                .secretFingerprint(secret.fingerprint())
                .secretKeyId(secret.keyId())
                .maxTokens(4096)
                .temperature(0.7)
                .isEnabled(1)
                .modelType("chat")
                .supportsThinking(0)
                .sortOrder(0)
                .userId(9L)
                .build();
    }
}
