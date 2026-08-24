package com.rush.rushaicodemother.orchestration.create;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 管理后台 recipe 内建交互能力的唯一事实所有者。
 *
 * <p>这些 slot 描述的是同一个管理工作台中的行为，不要求每个能力都产生一份独立补丁。
 * Planner 负责声明用户需要的能力，renderer 只有在承载该能力的工作台和数据补丁同时存在时
 * 才能确认覆盖，避免把孤立组件文件误报成可用功能。</p>
 */
public enum AdminRecipeCapability {

    SEARCH_BAR("search_bar", true, false),
    FORM_MODAL("form_modal", true, false),
    PRO_TABLE("pro_table", true, false),
    BULK_ACTIONS("bulk_actions", true, true),
    ADVANCED_FILTERS("advanced_filters", true, false),
    INVENTORY_DATA("inventory_data", false, true);

    private static final String DASHBOARD_SLOT = "dashboard_content";
    private static final String DATA_SLOT = "mock_data";
    private static final Map<String, AdminRecipeCapability> CAPABILITIES_BY_SLOT =
            Stream.of(values()).collect(Collectors.toUnmodifiableMap(
                    AdminRecipeCapability::slotId,
                    capability -> capability
            ));

    private final String slotId;
    private final boolean crud;
    private final boolean commerce;

    AdminRecipeCapability(String slotId, boolean crud, boolean commerce) {
        this.slotId = slotId;
        this.crud = crud;
        this.commerce = commerce;
    }

    public String slotId() {
        return slotId;
    }

    /** 管理 CRUD 模块需要的稳定能力 slot，顺序同时决定可读的计划输出。 */
    static List<String> crudSlotIds() {
        return Stream.of(values())
                .filter(capability -> capability.crud)
                .map(AdminRecipeCapability::slotId)
                .toList();
    }

    /** 商品管理模块需要的稳定能力 slot。 */
    static List<String> commerceSlotIds() {
        return Stream.of(values())
                .filter(capability -> capability.commerce)
                .map(AdminRecipeCapability::slotId)
                .toList();
    }

    /** 按 slot 标识解析管理能力；普通文件 slot 不属于本枚举。 */
    public static Optional<AdminRecipeCapability> fromSlotId(String slotId) {
        if (slotId == null || slotId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(CAPABILITIES_BY_SLOT.get(slotId));
    }

    /**
     * 判断本次合并后的模板作用域是否包含能力所需的真实承载物。
     *
     * <p>交互能力依赖工作台页面；库存能力还依赖类型化模拟数据。缺少任一承载物时必须
     * fail closed，让完整生成链路接管，而不是仅凭模板目录中的同名组件判定成功。</p>
     */
    public boolean isProvidedBy(Set<String> requestedSlots) {
        if (requestedSlots == null || !requestedSlots.contains(DASHBOARD_SLOT)) {
            return false;
        }
        return this != INVENTORY_DATA || requestedSlots.contains(DATA_SLOT);
    }
}
