package com.rush.rushaicodemother.orchestration.artifact;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RollbackPointTest {

    @Test
    void createdRollbackPointWithoutSnapshotPathMustBeRejected() {
        RollbackPoint rollbackPoint = RollbackPoint.created(
                7L,
                "task-7",
                "pre_generation_task-7",
                "/snapshots/7/pre_generation_task-7",
                "/projects/vue_project_7",
                "vue_project",
                "vue_project",
                3
        );
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
        RollbackPoint rollbackPoint = RollbackPoint.created(
                7L,
                "task-7",
                "pre_generation_task-7",
                "/snapshots/7/pre_generation_task-7",
                "/projects/vue_project_7",
                "vue_project",
                "vue_project",
                3
        );
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
        RollbackPoint rollbackPoint = RollbackPoint.created(
                7L,
                "task-7",
                "pre_generation_task-7",
                "/snapshots/7/pre_generation_task-7",
                "/projects/vue_project_7",
                "vue_project",
                "vue_project",
                -1
        );

        assertThrows(IllegalArgumentException.class, rollbackPoint::toArtifact);
    }
}
