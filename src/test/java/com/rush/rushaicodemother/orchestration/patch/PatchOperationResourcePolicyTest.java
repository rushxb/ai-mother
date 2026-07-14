package com.rush.rushaicodemother.orchestration.patch;

import com.rush.rushaicodemother.config.PatchExecutionProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchOperationResourcePolicyTest {

    @Test
    void shouldRejectOperationCountAboveLimit() {
        PatchExecutionProperties properties = properties(1, 20, 40);
        PatchOperationResourcePolicy policy = new PatchOperationResourcePolicy(properties);

        List<String> blockers = policy.validate(List.of(
                PatchOperation.add("a.txt", "a"),
                PatchOperation.add("b.txt", "b")
        ));

        assertEquals(List.of("batch:operation_limit_exceeded"), blockers);
    }

    @Test
    void shouldRejectPerOperationAndTotalContentBudgets() {
        PatchExecutionProperties properties = properties(10, 5, 8);
        PatchOperationResourcePolicy policy = new PatchOperationResourcePolicy(properties);

        List<String> blockers = policy.validate(List.of(
                PatchOperation.add("a.txt", "123456"),
                PatchOperation.replace("b.txt", "12", "34")
        ));

        assertTrue(blockers.contains("add:a.txt:operation_content_limit_exceeded"));
        assertTrue(blockers.contains("batch:total_content_limit_exceeded"));
    }

    private PatchExecutionProperties properties(int maxOperations,
                                                int maxOperationContentChars,
                                                int maxTotalContentChars) {
        PatchExecutionProperties properties = new PatchExecutionProperties();
        properties.setMaxOperations(maxOperations);
        properties.setMaxOperationContentChars(maxOperationContentChars);
        properties.setMaxTotalContentChars(maxTotalContentChars);
        return properties;
    }
}
