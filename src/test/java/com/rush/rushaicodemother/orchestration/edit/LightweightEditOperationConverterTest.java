package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.ai.model.EditOperation;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightweightEditOperationConverterTest {

    private final LightweightEditOperationConverter converter = new LightweightEditOperationConverter();

    @Test
    void rejectsProtectedFilesAfterWhitespaceCaseAndSeparatorNormalization() {
        List<PatchOperation> operations = converter.convert(List.of(
                modify(" package.json "),
                modify("config\\Package.JSON"),
                modify("src/VITE.CONFIG.ts"),
                modify("backend/GO.MOD"),
                modify("deploy/dockerFILE.prod"),
                modify("src/TSConfig.app.json")
        ));

        assertTrue(operations.isEmpty());
    }

    @Test
    void convertsSupportedOperationsAndNormalizesPathSeparators() {
        List<PatchOperation> operations = converter.convert(List.of(
                new EditOperation(" MODIFY ", " src\\App.vue ", null, null, "updated"),
                new EditOperation("replace", "src/view.ts", "old", "", null),
                new EditOperation("add", "src/new.ts", null, null, "new")
        ));

        assertEquals(3, operations.size());
        assertEquals("src/App.vue", operations.get(0).relativePath());
        assertEquals(PatchOperation.ACTION_MODIFY, operations.get(0).action());
        assertEquals("", operations.get(1).newContent());
        assertEquals(PatchOperation.ACTION_ADD, operations.get(2).action());
    }

    @Test
    void ignoresNullMalformedAndUnsupportedOperations() {
        List<PatchOperation> operations = converter.convert(List.of(
                new EditOperation("delete", "src/App.vue", null, null, null),
                new EditOperation("modify", " ", null, null, "content"),
                new EditOperation("modify", "src/Empty.vue", null, null, " ")
        ));

        assertTrue(operations.isEmpty());
        assertTrue(converter.convert(null).isEmpty());
    }

    private EditOperation modify(String relativePath) {
        return new EditOperation("modify", relativePath, null, null, "content");
    }
}
