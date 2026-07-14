package com.rush.rushaicodemother.orchestration.edit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrchestrationResultContractTest {

    @Test
    void agentEditResultShouldNormalizeNullableOutputAndDefensivelyCopyFiles() {
        List<String> changedFiles = new ArrayList<>(List.of("src/App.vue"));

        AgentEditResult result = new AgentEditResult(null, null, null, changedFiles, null, -1);
        changedFiles.add("src/main.ts");

        assertEquals("", result.taskId());
        assertEquals("", result.route());
        assertEquals("", result.summary());
        assertEquals("", result.status());
        assertEquals(0, result.repairRounds());
        assertEquals(List.of("src/App.vue"), result.changedFiles());
        assertThrows(UnsupportedOperationException.class, () -> result.changedFiles().add("src/router.ts"));
    }

    @Test
    void editValidationPlanShouldRequireLevelAndNormalizeOptionalFields() {
        assertThrows(
                NullPointerException.class,
                () -> new EditValidationPlan(null, "reason", List.of(), false)
        );

        EditValidationPlan plan = new EditValidationPlan(
                EditValidationPlan.ValidationLevel.FAST_CHECK,
                null,
                null,
                false
        );

        assertEquals("", plan.reason());
        assertNotNull(plan.changedFiles());
        assertEquals(List.of(), plan.changedFiles());
    }

    @Test
    void validationResultShouldNormalizeNullableOutputAndDefensivelyCopyDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("checkedFiles", 2);

        BackgroundValidationService.ValidationResult result =
                new BackgroundValidationService.ValidationResult(null, null, null, details);
        details.put("lateMutation", true);

        assertEquals("", result.taskId());
        assertEquals("", result.status());
        assertEquals("", result.message());
        assertEquals(Map.of("checkedFiles", 2), result.details());
        assertThrows(UnsupportedOperationException.class, () -> result.details().put("newKey", "value"));
    }
}
