package com.rush.rushaicodemother.memory;

import cn.hutool.crypto.digest.DigestUtil;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticMemoryGovernancePolicyTest {

    @Test
    void metadataMustBeScalarWhitelistedBoundedAndUnableToOverrideTrust() {
        String content = "memory content";

        Map<String, Object> governed = SemanticMemoryGovernancePolicy.governMetadata(
                Map.of(
                        "source", "generation_feedback",
                        "route", "heavy",
                        "trust", "trusted_system",
                        "unknown", Map.of("nested", "value")
                ),
                content
        );

        assertEquals("v2", governed.get("schemaVersion"));
        assertEquals("generation_feedback", governed.get("source"));
        assertEquals("untrusted_history", governed.get("trust"));
        assertEquals("heavy", governed.get("route"));
        assertEquals(DigestUtil.sha256Hex(content), governed.get("contentDigest"));
        assertFalse(governed.containsKey("unknown"));
        assertTrue(SemanticMemoryGovernancePolicy.utf8Length(
                cn.hutool.json.JSONUtil.toJsonStr(governed))
                <= SemanticMemoryGovernancePolicy.MAX_METADATA_UTF8_BYTES);
    }

    @Test
    void utf8TruncationMustPreserveUnicodeCodePointsAndTheByteBudget() {
        String content = "你🙂".repeat(20_000);

        String truncated = SemanticMemoryGovernancePolicy.truncateUtf8(content, 101);

        assertTrue(truncated.endsWith("..."));
        assertTrue(SemanticMemoryGovernancePolicy.utf8Length(truncated) <= 101);
        assertFalse(Character.isHighSurrogate(truncated.charAt(truncated.length() - 4)));
    }

    @Test
    void nonFiniteOrZeroEmbeddingsAndMissingTenantMustBeRejected() {
        String content = "invalid";
        Map<String, Object> metadata =
                SemanticMemoryGovernancePolicy.governMetadata(Map.of(), content);
        SemanticMemory missingTenant = new SemanticMemory(
                DigestUtil.sha256Hex("missing-tenant"), null, 1L, 2L, "task-1",
                MemoryType.TASK_OUTCOME, content, metadata,
                new float[]{1.0f, 0.0f}, Instant.now());
        SemanticMemory zero = new SemanticMemory(
                DigestUtil.sha256Hex("zero"), 3L, 1L, 2L, "task-1",
                MemoryType.TASK_OUTCOME, content, metadata,
                new float[]{0.0f, 0.0f}, Instant.now());

        assertThrows(IllegalArgumentException.class,
                () -> SemanticMemoryGovernancePolicy.validateMemory(missingTenant, 2));
        assertThrows(IllegalArgumentException.class,
                () -> SemanticMemoryGovernancePolicy.validateMemory(zero, 2));
    }
}
