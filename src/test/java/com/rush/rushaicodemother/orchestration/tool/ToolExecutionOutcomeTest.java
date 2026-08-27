package com.rush.rushaicodemother.orchestration.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionOutcomeTest {

    @Test
    void presentMutationEvidenceMustRequireAnExplicitPathList() {
        assertThrows(NullPointerException.class,
                () -> new ToolExecutionOutcome(false, "目标状态已满足", true, null));
    }

    @Test
    void explicitEmptyPathListMustRemainConfirmedNoOpEvidence() {
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                false, "目标状态已满足", true, List.of());

        assertTrue(outcome.mutationEvidencePresent());
        assertTrue(outcome.effectiveMutationPaths().isEmpty());
    }

    @Test
    void legacyOutcomeWithoutEvidenceMustKeepAcceptingNullPaths() {
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                false, "旧记录", false, null);

        assertFalse(outcome.mutationEvidencePresent());
        assertTrue(outcome.effectiveMutationPaths().isEmpty());
    }
}
