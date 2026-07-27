package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingDecisionCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultGenerationSlaPolicyTest {

    @Test
    void createUsesPreviewFirstEnvelope() {
        GenerationSlaProperties properties = new GenerationSlaProperties();
        DefaultGenerationSlaPolicy policy = new DefaultGenerationSlaPolicy(properties);

        GenerationSlaEnvelope envelope = policy.resolve(decision(
                GenerationMode.CREATE, GenerationRoutingDecisionCode.CREATE_TEMPLATE_FIRST),
                CodeGenTypeEnum.VUE_PROJECT);

        assertEquals("create-preview-first", envelope.profile());
        assertEquals(Duration.ofSeconds(60), envelope.firstPreviewTimeout());
        assertEquals(Duration.ofSeconds(45), envelope.firstPreviewCompletionReserve());
        assertEquals(Duration.ofMinutes(10), envelope.totalTimeout());
        assertEquals(2, envelope.toLimits().limit(GenerationBudgetKind.BUILD_EXECUTION));
        assertTrue(properties.isConfigurationValid());
    }

    @Test
    void saturationDecisionUsesContainedBudgetsInsteadOfNormalAgentBudget() {
        DefaultGenerationSlaPolicy policy = new DefaultGenerationSlaPolicy(new GenerationSlaProperties());

        GenerationSlaEnvelope normal = policy.resolve(decision(
                GenerationMode.AGENT_EDIT, GenerationRoutingDecisionCode.AGENT_EDIT_COMPLEXITY),
                CodeGenTypeEnum.VUE_PROJECT);
        GenerationSlaEnvelope saturated = policy.resolve(decision(
                GenerationMode.AGENT_EDIT, GenerationRoutingDecisionCode.TELEMETRY_SATURATION_CONTAINMENT),
                CodeGenTypeEnum.VUE_PROJECT);

        assertEquals("agent-edit-saturated", saturated.profile());
        assertTrue(saturated.totalTimeout().compareTo(normal.totalTimeout()) < 0);
        assertTrue(saturated.toLimits().limit(GenerationBudgetKind.TOOL_WRITE)
                < normal.toLimits().limit(GenerationBudgetKind.TOOL_WRITE));
        assertTrue(saturated.toLimits().limit(GenerationBudgetKind.BUILD_EXECUTION)
                < normal.toLimits().limit(GenerationBudgetKind.BUILD_EXECUTION));
    }

    private GenerationModeDecision decision(GenerationMode mode, GenerationRoutingDecisionCode code) {
        return GenerationModeDecision.of(
                mode, 0.8, "test", FallbackPolicy.NONE, ExpectedValidationLevel.BUILD, code);
    }
}
