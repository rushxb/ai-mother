package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.config.GenerationTaskAdmissionProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationQuote;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationTenantQuotaAdmissionPolicyTest {

    @Test
    void policyMustEnforceTenantTaskHeavyAndMonthlyBudgetBoundaries() {
        GenerationTaskAdmissionProperties properties = new GenerationTaskAdmissionProperties();
        properties.setMaxNonTerminalTasksPerTenant(16);
        properties.setMaxHeavyTasksPerTenant(4);
        properties.setMonthlyCreditLimitPerTenant(100L);
        GenerationTenantQuotaAdmissionPolicy policy = new GenerationTenantQuotaAdmissionPolicy(properties);

        assertDoesNotThrow(() -> policy.assertMayAdmit(context(
                GenerationMode.HEAVY_EXPERT, new GenerationTaskAdmissionSnapshot(0, 0, 15, 3, 89), 11)));
        assertThrows(BusinessException.class, () -> policy.assertMayAdmit(context(
                GenerationMode.AGENT_EDIT, new GenerationTaskAdmissionSnapshot(0, 0, 16, 0, 0), 1)));
        assertThrows(BusinessException.class, () -> policy.assertMayAdmit(context(
                GenerationMode.HEAVY_EXPERT, new GenerationTaskAdmissionSnapshot(0, 0, 3, 4, 0), 1)));
        assertThrows(BusinessException.class, () -> policy.assertMayAdmit(context(
                GenerationMode.LIGHT_EDIT, new GenerationTaskAdmissionSnapshot(0, 0, 3, 0, 90), 11)));
    }

    @Test
    void heavyCapacityMustNotBlockLightweightWork() {
        GenerationTaskAdmissionProperties properties = new GenerationTaskAdmissionProperties();
        properties.setMaxHeavyTasksPerTenant(1);
        GenerationTenantQuotaAdmissionPolicy policy = new GenerationTenantQuotaAdmissionPolicy(properties);

        assertDoesNotThrow(() -> policy.assertMayAdmit(context(
                GenerationMode.LIGHT_EDIT, new GenerationTaskAdmissionSnapshot(0, 0, 1, 1, 0), 1)));
    }

    @Test
    void preflightMustReserveWorstCaseHeavyCapacityAndMonthlyHeadroom() {
        GenerationTaskAdmissionProperties properties = new GenerationTaskAdmissionProperties();
        properties.setMaxNonTerminalTasksPerTenant(16);
        properties.setMaxHeavyTasksPerTenant(1);
        properties.setMonthlyCreditLimitPerTenant(100L);
        GenerationTenantQuotaAdmissionPolicy policy = new GenerationTenantQuotaAdmissionPolicy(properties);

        assertThrows(BusinessException.class, () -> policy.assertMayPreflight(preflightContext(
                new GenerationTaskAdmissionSnapshot(0, 0, 1, 1, 0), 1)));
        assertThrows(BusinessException.class, () -> policy.assertMayPreflight(preflightContext(
                new GenerationTaskAdmissionSnapshot(0, 0, 1, 0, 92), 9)));
        assertDoesNotThrow(() -> policy.assertMayPreflight(preflightContext(
                new GenerationTaskAdmissionSnapshot(0, 0, 1, 0, 91), 9)));
    }

    private GenerationTaskAdmissionContext context(GenerationMode mode,
                                                   GenerationTaskAdmissionSnapshot snapshot,
                                                   long reservedCredit) {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        GenerationTaskCommand command = new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                "task-1", 1L, 7L, 100L, "修改项目", CodeGenTypeEnum.VUE_PROJECT,
                mode, 0.9, "测试", FallbackPolicy.NONE, ExpectedValidationLevel.BUILD,
                "", now, now.plusSeconds(600));
        return new GenerationTaskAdmissionContext(
                command, snapshot, new GenerationCreditReservationQuote(100_000, reservedCredit, "v1"));
    }

    private GenerationTaskPreflightAdmissionContext preflightContext(
            GenerationTaskAdmissionSnapshot snapshot,
            long reservedCredit) {
        return new GenerationTaskPreflightAdmissionContext(
                100L,
                7L,
                CodeGenTypeEnum.VUE_PROJECT,
                IntentProfile.unknown(),
                snapshot,
                new GenerationCreditReservationQuote(900_000L, reservedCredit, "preflight-v1"));
    }
}
