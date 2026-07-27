package com.rush.rushaicodemother.orchestration.create;

import java.util.List;

/**
 * 插槽分组的不可变数据载体。
 */
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
