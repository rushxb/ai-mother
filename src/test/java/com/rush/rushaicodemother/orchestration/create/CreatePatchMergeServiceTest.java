package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CreatePatchMergeServiceTest {

    private final CreatePatchMergeService service = new CreatePatchMergeService();

    @Test
    void shouldKeepMultipleMarkerPatchesForSameFileWithoutWholeFileOverwrite() {
        SlotPatchPlan plan = service.merge(List.of(
                PatchOperation.insertBeforeMarker("cmd/server/main.go", "// @AI_INJECT_MODULE_WIRING: register", "repo := NewRepo()"),
                PatchOperation.goAddImport("cmd/server/main.go", "backend-template/internal/modules/product")
        ));

        assertEquals(2, plan.originalOperationCount());
        assertEquals(2, plan.mergedOperationCount());
    }

    @Test
    void shouldRejectMultipleWholeFileWritesToSameFile() {
        assertThrows(IllegalArgumentException.class, () -> service.merge(List.of(
                PatchOperation.modify("src/data/adminData.ts", "export const a = []"),
                PatchOperation.modify("src/data/adminData.ts", "export const b = []")
        )));
    }
}
