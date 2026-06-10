package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.orchestration.patch.PatchOperation;

import java.util.List;

public record SlotPatchPlan(
        List<PatchOperation> operations,
        int originalOperationCount,
        int mergedOperationCount
) {
    public SlotPatchPlan {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
