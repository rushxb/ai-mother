package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecipeRenderResultTest {

    @Test
    void shouldCreateDefensiveImmutableCopiesAndComputePayloadSize() {
        List<String> slots = new ArrayList<>(List.of("mock_data"));
        List<PatchOperation> operations = new ArrayList<>(List.of(
                PatchOperation.modify("src/data.ts", "content")
        ));

        RecipeRenderResult result = RecipeRenderResult.of(slots, operations, "summary", null);
        slots.clear();
        operations.clear();

        assertEquals(List.of("mock_data"), result.filledSlots());
        assertEquals(List.of("mock_data"), result.requestedSlots());
        assertEquals(List.of(), result.unfilledSlots());
        assertEquals(1, result.patchOperations().size());
        assertEquals("content".length(), result.totalChars());
        assertThrows(UnsupportedOperationException.class, () -> result.filledSlots().add("other"));
        assertThrows(UnsupportedOperationException.class, () -> result.patchOperations().clear());
    }
}
