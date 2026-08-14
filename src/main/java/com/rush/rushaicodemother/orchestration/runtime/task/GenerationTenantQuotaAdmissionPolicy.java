package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.config.GenerationTaskAdmissionProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.monitor.GenerationTenantAdmissionMetricsCollector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 防止单个租户耗尽共享生成容量或突破周期预算。 */
@Component
public class GenerationTenantQuotaAdmissionPolicy implements GenerationTaskAdmissionPolicy {

    private final GenerationTaskAdmissionProperties properties;
    private final GenerationTenantAdmissionMetricsCollector metrics;

    @Autowired
    public GenerationTenantQuotaAdmissionPolicy(GenerationTaskAdmissionProperties properties,
                                                 GenerationTenantAdmissionMetricsCollector metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    GenerationTenantQuotaAdmissionPolicy(GenerationTaskAdmissionProperties properties) {
        this(properties, GenerationTenantAdmissionMetricsCollector.noOp());
    }

    @Override
    public void assertMayAdmit(GenerationTaskAdmissionContext context) {
        GenerationTaskAdmissionSnapshot snapshot = context.snapshot();
        assertTenantTaskCapacity(snapshot);
        assertHeavyCapacity(snapshot, context.command().mode() == GenerationMode.HEAVY_EXPERT);
        assertMonthlyBudget(snapshot, context.quote().reservedCredit());
    }

    @Override
    public void assertMayPreflight(GenerationTaskPreflightAdmissionContext context) {
        GenerationTaskAdmissionSnapshot snapshot = context.snapshot();
        assertTenantTaskCapacity(snapshot);
        // 澄清可以把轻量画像提升到 Heavy；模型调用前必须先保守占用这条容量判断。
        assertHeavyCapacity(snapshot, true);
        assertMonthlyBudget(snapshot, context.upperBoundQuote().reservedCredit());
    }

    private void assertTenantTaskCapacity(GenerationTaskAdmissionSnapshot snapshot) {
        int taskLimit = properties.getMaxNonTerminalTasksPerTenant();
        if (snapshot.tenantNonTerminalTasks() >= taskLimit) {
            metrics.record("tenant_tasks");
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "当前租户同时进行中的生成任务已达到上限（" + taskLimit + "）"
            );
        }
    }

    private void assertHeavyCapacity(GenerationTaskAdmissionSnapshot snapshot,
                                     boolean heavyCapacityRequired) {
        int heavyLimit = properties.getMaxHeavyTasksPerTenant();
        if (heavyCapacityRequired && snapshot.tenantHeavyNonTerminalTasks() >= heavyLimit) {
            metrics.record("tenant_heavy");
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "当前租户同时进行中的专家生成任务已达到上限（" + heavyLimit + "）"
            );
        }
    }

    private void assertMonthlyBudget(GenerationTaskAdmissionSnapshot snapshot,
                                     long requiredCredit) {
        long monthlyLimit = properties.getMonthlyCreditLimitPerTenant();
        long remaining = Math.max(0L, monthlyLimit - snapshot.tenantMonthlyCreditUsage());
        if (requiredCredit > remaining) {
            metrics.record("monthly_budget");
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "当前租户本月生成预算不足，本任务需要预留 "
                            + requiredCredit + " 积分"
            );
        }
    }
}
