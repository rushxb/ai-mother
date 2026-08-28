package com.rush.rushaicodemother.mapper;

/** MyBatis 租户场景已结算尝试成本与成功交付数聚合行。 */
public record TenantGenerationScenarioCostRow(
        String route,
        String targetCodeGenType,
        Long settledTasks,
        Long successfulDeliveries,
        Long totalCreditCost
) {
}
