package com.rush.rushaicodemother.service.credit;

import com.rush.rushaicodemother.config.GenerationCreditReservationProperties;
import com.rush.rushaicodemother.config.UserCreditProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaEnvelope;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationCreditReservationPolicyTest {

    @Test
    void quoteMustCombineRouteBudgetAndProjectComplexityWithCurrentCreditPricing() {
        GenerationCreditReservationPolicy policy = policy();

        GenerationCreditReservationQuote lightVue = policy.quote(command(
                "task-light", GenerationMode.LIGHT_EDIT, CodeGenTypeEnum.VUE_PROJECT));
        GenerationCreditReservationQuote readOnlyVue = policy.quote(command(
                "task-read-only", GenerationMode.READ_ONLY, CodeGenTypeEnum.VUE_PROJECT));
        GenerationCreditReservationQuote heavyFullStack = policy.quote(command(
                "task-heavy", GenerationMode.HEAVY_EXPERT, CodeGenTypeEnum.FULL_STACK_PROJECT));

        assertEquals(96_000L, lightVue.estimatedTokens());
        assertEquals(1L, lightVue.reservedCredit());
        assertEquals(48_000L, readOnlyVue.estimatedTokens());
        assertEquals(1L, readOnlyVue.reservedCredit());
        assertTrue(readOnlyVue.pricingReference().contains(":READ_ONLY:"));
        assertEquals(1_050_000L, heavyFullStack.estimatedTokens());
        assertEquals(11L, heavyFullStack.reservedCredit());
        assertTrue(heavyFullStack.pricingReference().startsWith(
                "route-token-budget-v1:HEAVY_EXPERT:FULL_STACK_PROJECT:test-profile:"));
    }

    private GenerationCreditReservationPolicy policy() {
        UserCreditProperties creditProperties = new UserCreditProperties();
        creditProperties.setTokensPerCredit(100_000L);
        return new GenerationCreditReservationPolicy(
                new GenerationCreditReservationProperties(),
                new UserCreditCostCalculator(creditProperties)
        );
    }

    private GenerationTaskCommand command(String taskId,
                                          GenerationMode mode,
                                          CodeGenTypeEnum type) {
        Instant submittedAt = Instant.parse("2026-07-18T00:00:00Z");
        GenerationSlaEnvelope envelope = new GenerationSlaEnvelope(
                "test-profile",
                Duration.ofMinutes(1),
                Duration.ofMinutes(10),
                Duration.ofMinutes(2),
                Duration.ofMillis(500),
                Map.of(
                        GenerationBudgetKind.ROOT_MODEL_ATTEMPT, 2,
                        GenerationBudgetKind.MODEL_TURN, 8,
                        GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT, 2,
                        GenerationBudgetKind.TOOL_WRITE, 10,
                        GenerationBudgetKind.BUILD_EXECUTION, 1,
                        GenerationBudgetKind.REPAIR_ROUND, 1
                ),
                "test"
        );
        return new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                taskId,
                1L,
                7L,
                100L,
                "build application",
                type,
                mode,
                0.9,
                "test",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD,
                "",
                com.rush.rushaicodemother.orchestration.router.GenerationRoutingDecisionCode.UNKNOWN,
                envelope,
                submittedAt,
                envelope.totalDeadline(submittedAt)
        );
    }
}
