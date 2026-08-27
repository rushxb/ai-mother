package com.rush.rushaicodemother.orchestration.artifact;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RollbackPointTest {

    @Test
    void createdRollbackPointWithoutSnapshotPathMustBeRejected() {
        RollbackPoint rollbackPoint = created(3);
        Map<String, Object> damagedPayload = new LinkedHashMap<>(rollbackPoint.toPayload());
        damagedPayload.put("snapshotPath", "");
        GenerationArtifact damagedArtifact = new GenerationArtifact(
                RollbackPoint.KEY,
                "Orchestrator",
                "Rollback point",
                damagedPayload,
                LocalDateTime.now()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> RollbackPoint.fromArtifact(damagedArtifact, 7L, "task-7")
        );
    }

    @Test
    void unknownRollbackPointStatusMustBeRejected() {
        RollbackPoint rollbackPoint = created(3);
        Map<String, Object> damagedPayload = new LinkedHashMap<>(rollbackPoint.toPayload());
        damagedPayload.put("status", "restored");
        GenerationArtifact damagedArtifact = new GenerationArtifact(
                RollbackPoint.KEY,
                "Orchestrator",
                "Rollback point",
                damagedPayload,
                LocalDateTime.now()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> RollbackPoint.fromArtifact(damagedArtifact, 7L, "task-7")
        );
    }

    @Test
    void skippedRollbackPointWithSnapshotFactsMustBeRejected() {
        RollbackPoint rollbackPoint = RollbackPoint.skipped(
                7L,
                "task-7",
                "/projects/vue_project_7",
                "vue_project",
                "vue_project",
                "snapshot_create_failed"
        );
        Map<String, Object> damagedPayload = new LinkedHashMap<>(rollbackPoint.toPayload());
        damagedPayload.put("snapshotName", "pre_generation_task-7");
        damagedPayload.put("snapshotPath", "/snapshots/7/pre_generation_task-7");
        damagedPayload.put("fileCount", 3);
        GenerationArtifact damagedArtifact = new GenerationArtifact(
                RollbackPoint.KEY,
                "Orchestrator",
                "Rollback point",
                damagedPayload,
                LocalDateTime.now()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> RollbackPoint.fromArtifact(damagedArtifact, 7L, "task-7")
        );
    }

    @Test
    void negativeFileCountMustBeRejectedBeforePersistence() {
        RollbackPoint rollbackPoint = created(-1);

        assertThrows(IllegalArgumentException.class, rollbackPoint::toArtifact);
    }

    @Test
    void legacyRollbackPointMustRemainParseableButUntrusted() {
        Map<String, Object> payload = new LinkedHashMap<>(created(1).toPayload());
        payload.put("schemaVersion", RollbackPoint.LEGACY_SCHEMA_VERSION);
        payload.remove("snapshotId");
        payload.remove("manifestSha256");
        payload.remove("scope");
        payload.remove("executionEpoch");

        RollbackPoint parsed = RollbackPoint.fromArtifact(
                GenerationArtifact.of(RollbackPoint.KEY, "Orchestrator", "legacy", payload),
                7L,
                "task-7"
        );

        org.junit.jupiter.api.Assertions.assertTrue(parsed.created());
        org.junit.jupiter.api.Assertions.assertFalse(parsed.trustedForSnapshotConsumption());
    }

    private RollbackPoint created(int fileCount) {
        return RollbackPoint.created(
                7L,
                "task-7",
                "pre_generation_task-7",
                "11111111-1111-1111-1111-111111111111",
                "a".repeat(64),
                ".",
                2L,
                "/snapshots/7/11111111-1111-1111-1111-111111111111",
                "/projects/vue_project_7",
                "vue_project",
                "vue_project",
                fileCount
        );
    }
}
