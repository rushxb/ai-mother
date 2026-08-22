package com.rush.rushaicodemother.orchestration.artifact;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchitecturePlanTest {

    @Test
    void shouldRoundTripVersionedArtifact() {
        ArchitecturePlan plan = new ArchitecturePlan(
                List.of("frontend", "backend"),
                List.of("保持 API 契约稳定"),
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                true
        );

        GenerationArtifact artifact = plan.toArtifact();

        assertEquals(ArchitecturePlan.KEY, artifact.key());
        assertEquals("v1", artifact.payload().get("schemaVersion"));
        assertEquals(
                plan,
                ArchitecturePlan.fromArtifact(artifact, CodeGenTypeEnum.FULL_STACK_PROJECT)
        );
    }

    @Test
    void malformedBooleanMustNotBeCoercedDuringCheckpointRestore() {
        GenerationArtifact artifact = GenerationArtifact.of(
                ArchitecturePlan.KEY,
                "Architect",
                "架构规划",
                Map.of(
                        "modules", List.of("app"),
                        "targetType", CodeGenTypeEnum.VUE_PROJECT.getValue(),
                        "parallelizable", "yes"
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> ArchitecturePlan.fromArtifact(artifact, CodeGenTypeEnum.VUE_PROJECT)
        );
    }
}
