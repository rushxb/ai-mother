package com.rush.rushaicodemother.orchestration.artifact;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextSummaryArtifactTest {

    @Test
    void shouldRoundTripCanonicalContextSummary() {
        ContextSummaryArtifact original = summary();

        GenerationArtifact persisted = original.toArtifact();
        ContextSummaryArtifact restored = ContextSummaryArtifact.fromArtifact(persisted);

        assertEquals(ContextSummaryArtifact.KEY, persisted.key());
        assertEquals("v1", persisted.payload().get("schemaVersion"));
        assertEquals(List.of("src/App.vue"), restored.selectedFiles());
        assertEquals("intent_selected_files", restored.contextMode());
        assertEquals(1, restored.indexHitCount());
        assertEquals(original.toArtifact().payload(), restored.toArtifact().payload());
    }

    @Test
    void persistedSkillIdsMustMatchValidatedSkillPayloads() {
        GenerationArtifact canonical = summary().toArtifact();
        Map<String, Object> corruptedPayload = new LinkedHashMap<>(canonical.payload());
        corruptedPayload.put("skillIds", List.of("foreign-skill"));
        GenerationArtifact corrupted = GenerationArtifact.of(
                ContextSummaryArtifact.KEY,
                "Context",
                "损坏的项目上下文",
                corruptedPayload
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ContextSummaryArtifact.fromArtifact(corrupted)
        );

        assertTrue(exception.getMessage().contains("skillIds"));
    }

    private ContextSummaryArtifact summary() {
        return ContextSummaryArtifact.create(
                new ContextSummaryArtifact.RepositoryContext(
                        "management",
                        List.of("src/App.vue"),
                        12,
                        4,
                        List.of(Map.of("path", "src/App.vue", "score", 10)),
                        "intent_selected_files",
                        "src/App.vue 包含管理后台主页"
                ),
                new ContextSummaryArtifact.ContextProtection(
                        "a".repeat(64),
                        true,
                        false,
                        120
                ),
                new ContextSummaryArtifact.AgentGuidance(
                        "保留现有路由",
                        true,
                        List.of(Map.of("id", "crud-form", "title", "CRUD Form")),
                        List.of(Map.of("id", "frontend-design", "title", "Frontend Design"))
                )
        );
    }
}
