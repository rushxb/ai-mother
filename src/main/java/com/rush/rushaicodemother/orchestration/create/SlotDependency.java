package com.rush.rushaicodemother.orchestration.create;

/**
 * 插槽依赖的不可变数据载体。
 */
public record SlotDependency(
        String slotId,
        String dependsOnSlotId
) {
}
