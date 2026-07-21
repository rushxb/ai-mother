package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiModelConnectionTesterTest {

    @Test
    void connectionProbeMustResolveCredentialAtProviderBuilderBoundary() {
        AiModelSecretService secretService = mock(AiModelSecretService.class);
        when(secretService.resolve("protected-reference", "a".repeat(64))).thenThrow(
                new BusinessException(ErrorCode.OPERATION_ERROR, "secret unavailable"));
        AiModelRuntimeConfiguration configuration = new AiModelRuntimeConfiguration(
                "custom", "model", "chat", "https://models.example.com/v1",
                "protected-reference", "a".repeat(64), "kek-v1", 256, 0.1, false);

        var result = new AiModelConnectionTester(secretService).test(configuration);

        assertFalse(result.getSuccess());
        verify(secretService).resolve("protected-reference", "a".repeat(64));
    }
}
