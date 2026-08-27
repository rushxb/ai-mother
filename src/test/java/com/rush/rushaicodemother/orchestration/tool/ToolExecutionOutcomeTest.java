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
        assertFalse(outcome.workspaceInvalidated());
    }

    @Test
    void workspaceInvalidationMustBePersistedWithoutPretendingToBePathMutation() {
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                false, "已回滚工作区", false, List.of(), true);

        assertTrue(outcome.workspaceInvalidated());
        assertFalse(outcome.mutationEvidencePresent());
        assertTrue(outcome.effectiveMutationPaths().isEmpty());
    }

    @Test
    void workspaceInvalidationAndPathMutationEvidenceMustBeMutuallyExclusive() {
        assertThrows(IllegalArgumentException.class, () -> new ToolExecutionOutcome(
                false, "证据冲突", true, List.of("src/App.vue"), true));
    }

    @Test
    void errorOutcomeMustClearEveryWorkspaceSuccessFact() {
        ToolExecutionOutcome outcome = new ToolExecutionOutcome(
                true, "回滚失败", false, List.of(), true);

        assertTrue(outcome.error());
        assertFalse(outcome.workspaceInvalidated());
        assertFalse(outcome.mutationEvidencePresent());
    }
}
