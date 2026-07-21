package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.config.GenerationTaskAdmissionProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationTaskConcurrencyAdmissionPolicyTest {

    @Test
    void policyMustRejectTheNextTaskAtTheConfiguredUserOutstandingLimit() {
        GenerationTaskAdmissionProperties properties = new GenerationTaskAdmissionProperties();
        properties.setMaxNonTerminalTasksPerUser(4);
        GenerationTaskConcurrencyAdmissionPolicy policy =
                new GenerationTaskConcurrencyAdmissionPolicy(properties);

        assertDoesNotThrow(() -> policy.assertMayCreate(3));
        assertThrows(BusinessException.class, () -> policy.assertMayCreate(4));
        assertThrows(IllegalArgumentException.class, () -> policy.assertMayCreate(-1));
    }
}
