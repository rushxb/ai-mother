package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.orchestration.patch.PatchOperation;

import java.util.List;

/**
 * 插槽补丁计划的不可变数据载体。
 */
public record SlotPatchPlan(
        List<PatchOperation> operations,
        int originalOperationCount,
        int mergedOperationCount
) {
    public SlotPatchPlan {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
