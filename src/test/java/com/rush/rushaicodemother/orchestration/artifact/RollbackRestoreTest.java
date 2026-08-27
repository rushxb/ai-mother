package com.rush.rushaicodemother.orchestration.artifact;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackRestoreTest {

    @Test
    void shouldRoundTripRestoreResultWithExecutionIdentity() {
        RollbackRestore original = RollbackRestore.restored(
                7L,
                "task-7",
                "rollback_to_last_stable_snapshot_or_manual_retry",
                "pre_generation_task-7",
                "11111111-1111-1111-1111-111111111111",
                "b".repeat(64),
                ".",
                2L,
                "target/snapshots/7/11111111-1111-1111-1111-111111111111",
                "target/workspaces/7",
                "target/snapshots/7/failed-task-7",
                12
        );

        GenerationArtifact persisted = original.toArtifact();
        RollbackRestore restored = RollbackRestore.fromArtifact(persisted, 7L, "task-7");

        assertEquals(RollbackRestore.KEY, persisted.key());
        assertEquals(original, restored);
    }

    @Test
    void restoredStatusMustNotAcceptSkippedPayloadShape() {
        Map<String, Object> payload = new LinkedHashMap<>(RollbackRestore.skipped(
                7L,
                "task-7",
                "manual_retry_without_snapshot",
                "",
                "",
                "rollback_strategy_not_snapshot"
        ).toPayload());
        payload.put("status", "restored");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> RollbackRestore.fromArtifact(
                        GenerationArtifact.of(
                                RollbackRestore.KEY,
                                "Orchestrator",
                                "Rollback restore",
                                payload
                        ),
                        7L,
                        "task-7"
                )
        );

        assertTrue(exception.getMessage().contains("snapshotPath"));
    }

    @Test
    void legacyRestoreMustRemainParseableButNotTrustedForReplay() {
        Map<String, Object> payload = new LinkedHashMap<>(RollbackRestore.skipped(
                7L,
                "task-7",
                "manual_retry_without_snapshot",
                "",
                "",
                "legacy"
        ).toPayload());
        payload.put("schemaVersion", RollbackRestore.LEGACY_SCHEMA_VERSION);
        payload.keySet().removeAll(java.util.Set.of(
                "snapshotName", "snapshotId", "manifestSha256", "scope", "executionEpoch"));

        RollbackRestore parsed = RollbackRestore.fromArtifact(
                GenerationArtifact.of(RollbackRestore.KEY, "Orchestrator", "legacy", payload),
                7L,
                "task-7"
        );

        org.junit.jupiter.api.Assertions.assertFalse(parsed.trustedForReplay());
    }
}
