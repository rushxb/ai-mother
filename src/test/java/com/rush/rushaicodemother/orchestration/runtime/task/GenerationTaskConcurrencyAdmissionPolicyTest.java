package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.config.GenerationTaskAdmissionProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationQuote;
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

        assertDoesNotThrow(() -> policy.assertUserCapacity(3));
        assertThrows(BusinessException.class, () -> policy.assertUserCapacity(4));
        assertThrows(IllegalArgumentException.class, () -> policy.assertUserCapacity(-1));
    }

    @Test
    void applicationCapacityMustBeOwnedByTheDedicatedAppPolicy() {
        GenerationTaskConcurrencyAdmissionPolicy policy =
                new GenerationTaskConcurrencyAdmissionPolicy(new GenerationTaskAdmissionProperties());
        GenerationTaskPreflightAdmissionContext context = new GenerationTaskPreflightAdmissionContext(
                100L,
                7L,
                CodeGenTypeEnum.VUE_PROJECT,
                IntentProfile.unknown(),
                new GenerationTaskAdmissionSnapshot(0, 1, 1, 0, 0L),
                new GenerationCreditReservationQuote(1L, 1L, "preflight-test")
        );

        assertDoesNotThrow(() -> policy.assertMayPreflight(context));
    }
}
