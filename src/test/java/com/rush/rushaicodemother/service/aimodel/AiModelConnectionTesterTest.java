package com.rush.rushaicodemother.service.aimodel;

import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.model.enums.ModelInvocationBillingMode;
import com.rush.rushaicodemother.model.enums.ModelInvocationPurpose;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiModelConnectionTesterTest {

    @Test
    void connectionProbeMustUseTheAuditedPhysicalModelFactory() {
        StreamingModelFactory modelFactory = mock(StreamingModelFactory.class);
        AiModelRuntimeConfiguration configuration = new AiModelRuntimeConfiguration(
                "custom", "model", "chat", "https://models.example.com/v1",
                "protected-reference", "a".repeat(64), "kek-v1", 256, 0.1, false);

        when(modelFactory.testConnection(configuration))
                .thenThrow(new IllegalStateException("secret unavailable"));

        var result = new AiModelConnectionTester(modelFactory).test(configuration, 9L);

        assertFalse(result.getSuccess());
        verify(modelFactory).testConnection(configuration);
    }

    @Test
    void connectionProbeMustBindAnExplicitExemptBillingContext() {
        StreamingModelFactory modelFactory = mock(StreamingModelFactory.class);
        AiModelRuntimeConfiguration configuration = new AiModelRuntimeConfiguration(
                "custom", "model", "chat", "https://models.example.com/v1",
                "protected-reference", "a".repeat(64), "kek-v1", 256, 0.1, false);
        when(modelFactory.testConnection(configuration)).thenAnswer(invocation -> {
            MonitorContext context = MonitorContextHolder.getContext();
            assertEquals("9", context.getUserId());
            assertTrue(context.getTaskId().startsWith("connection-test:"));
            assertEquals(ModelInvocationPurpose.CONNECTION_TEST, context.getInvocationPurpose());
            assertEquals(ModelInvocationBillingMode.EXEMPT, context.getBillingMode());
            assertEquals("admin_connectivity_probe", context.getBillingExemptionReason());
            return "OK";
        });

        var result = new AiModelConnectionTester(modelFactory).test(configuration, 9L);

        assertTrue(result.getSuccess());
        org.junit.jupiter.api.Assertions.assertNull(MonitorContextHolder.getContext());
    }
}
