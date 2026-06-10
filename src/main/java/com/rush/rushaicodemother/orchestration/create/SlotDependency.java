package com.rush.rushaicodemother.orchestration.create;

public record SlotDependency(
        String slotId,
        String dependsOnSlotId
) {
}
