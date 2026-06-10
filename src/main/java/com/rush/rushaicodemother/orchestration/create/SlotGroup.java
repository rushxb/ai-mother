package com.rush.rushaicodemother.orchestration.create;

import java.util.List;

public record SlotGroup(
        String groupId,
        String templateId,
        String moduleId,
        List<String> slotIds,
        int order
) {
    public SlotGroup {
        slotIds = slotIds == null ? List.of() : List.copyOf(slotIds);
    }
}
