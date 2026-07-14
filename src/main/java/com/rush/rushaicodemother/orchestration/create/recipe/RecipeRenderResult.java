package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.orchestration.create.TemplateVariableManifest;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;

import java.util.List;

/**
 * Immutable output of deterministic CREATE recipe rendering.
 */
public record RecipeRenderResult(
        List<String> filledSlots,
        List<PatchOperation> patchOperations,
        int totalChars,
        String summary,
        TemplateVariableManifest manifest
) {

    public RecipeRenderResult {
        filledSlots = List.copyOf(filledSlots == null ? List.of() : filledSlots);
        patchOperations = List.copyOf(patchOperations == null ? List.of() : patchOperations);
        if (totalChars < 0) {
            throw new IllegalArgumentException("totalChars must not be negative");
        }
        summary = summary == null ? "" : summary;
    }

    public static RecipeRenderResult of(List<String> filledSlots,
                                        List<PatchOperation> patchOperations,
                                        String summary,
                                        TemplateVariableManifest manifest) {
        List<PatchOperation> operations = List.copyOf(patchOperations == null ? List.of() : patchOperations);
        int totalChars = operations.stream().mapToInt(RecipeRenderResult::payloadLength).sum();
        return new RecipeRenderResult(filledSlots, operations, totalChars, summary, manifest);
    }

    public static RecipeRenderResult empty() {
        return new RecipeRenderResult(List.of(), List.of(), 0, "", null);
    }

    public boolean available() {
        return !patchOperations.isEmpty();
    }

    private static int payloadLength(PatchOperation operation) {
        if (operation == null) {
            return 0;
        }
        String content = operation.content();
        if (content == null || content.isBlank()) {
            content = operation.newContent();
        }
        return content == null ? 0 : content.length();
    }
}
