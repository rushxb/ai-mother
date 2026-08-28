package com.rush.rushaicodemother.service.trace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationPromptSelectionProvenanceTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Test
    void canonicalizeMustProduceImmutableOrderIndependentIdentity() {
        GenerationPromptSelectionProvenance second = new GenerationPromptSelectionProvenance(
                "page-review", "v1", "stable", HASH_B, HASH_A);
        GenerationPromptSelectionProvenance first = new GenerationPromptSelectionProvenance(
                "codegen-vue-project", "v2", "canary", HASH_A, HASH_A);

        List<GenerationPromptSelectionProvenance> canonical =
                GenerationPromptSelectionProvenance.canonicalize(List.of(second, first));

        assertEquals(List.of(first, second), canonical);
        assertThrows(UnsupportedOperationException.class, () -> canonical.add(first));
    }
}
