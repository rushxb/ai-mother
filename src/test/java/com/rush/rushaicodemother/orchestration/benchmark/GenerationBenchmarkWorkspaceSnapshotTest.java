package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationBenchmarkWorkspaceSnapshotTest {

    @Test
    void logicalIdentityMustAllowVersionedCrossTypeWorkspaceComparison() {
        GenerationBenchmarkWorkspaceSnapshot baseline = new GenerationBenchmarkWorkspaceSnapshot(
                Path.of("source-vue"),
                Map.of("src/App.vue", "old"),
                new GenerationBenchmarkWorkspaceIdentity(
                        101L, CodeGenTypeEnum.VUE_PROJECT, CodeGenTypeEnum.FULL_STACK_PROJECT)
        );
        GenerationBenchmarkWorkspaceSnapshot published = new GenerationBenchmarkWorkspaceSnapshot(
                Path.of("published-fullstack"),
                Map.of("frontend/src/App.vue", "new", "backend/main.go", "backend"),
                new GenerationBenchmarkWorkspaceIdentity(
                        101L, CodeGenTypeEnum.FULL_STACK_PROJECT, CodeGenTypeEnum.FULL_STACK_PROJECT)
        );

        assertEquals(
                java.util.Set.of("src/App.vue", "frontend/src/App.vue", "backend/main.go"),
                baseline.changedPaths(published)
        );
    }

    @Test
    void logicalIdentityMustRejectAnotherApplicationOrUnexpectedTargetType() {
        GenerationBenchmarkWorkspaceSnapshot baseline = snapshot(
                101L, CodeGenTypeEnum.VUE_PROJECT, CodeGenTypeEnum.FULL_STACK_PROJECT);

        assertThrows(IllegalArgumentException.class, () -> baseline.changedPaths(snapshot(
                202L, CodeGenTypeEnum.FULL_STACK_PROJECT, CodeGenTypeEnum.FULL_STACK_PROJECT)));
        assertThrows(IllegalArgumentException.class, () -> baseline.changedPaths(snapshot(
                101L, CodeGenTypeEnum.VUE_PROJECT, CodeGenTypeEnum.VUE_PROJECT)));
    }

    @Test
    void unscopedSnapshotsMustStillRequireTheSamePhysicalRoot() {
        GenerationBenchmarkWorkspaceSnapshot baseline = new GenerationBenchmarkWorkspaceSnapshot(
                Path.of("source"), Map.of());
        GenerationBenchmarkWorkspaceSnapshot current = new GenerationBenchmarkWorkspaceSnapshot(
                Path.of("published"), Map.of());

        assertThrows(IllegalArgumentException.class, () -> baseline.changedPaths(current));
    }

    private GenerationBenchmarkWorkspaceSnapshot snapshot(
            long appId,
            CodeGenTypeEnum capturedType,
            CodeGenTypeEnum expectedTargetType
    ) {
        return new GenerationBenchmarkWorkspaceSnapshot(
                Path.of("workspace-" + appId + "-" + capturedType.getValue()),
                Map.of("README.md", "digest"),
                new GenerationBenchmarkWorkspaceIdentity(appId, capturedType, expectedTargetType)
        );
    }
}
