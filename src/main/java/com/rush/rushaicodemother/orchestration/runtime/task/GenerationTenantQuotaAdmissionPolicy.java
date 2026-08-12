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
        int taskLimit = properties.getMaxNonTerminalTasksPerTenant();
        if (snapshot.tenantNonTerminalTasks() >= taskLimit) {
            metrics.record("tenant_tasks");
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "当前租户同时进行中的生成任务已达到上限（" + taskLimit + "）"
            );
        }
        int heavyLimit = properties.getMaxHeavyTasksPerTenant();
        if (context.command().mode() == GenerationMode.HEAVY_EXPERT
                && snapshot.tenantHeavyNonTerminalTasks() >= heavyLimit) {
            metrics.record("tenant_heavy");
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "当前租户同时进行中的专家生成任务已达到上限（" + heavyLimit + "）"
            );
        }
        long monthlyLimit = properties.getMonthlyCreditLimitPerTenant();
        long remaining = Math.max(0L, monthlyLimit - snapshot.tenantMonthlyCreditUsage());
        if (context.quote().reservedCredit() > remaining) {
            metrics.record("monthly_budget");
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "当前租户本月生成预算不足，本任务需要预留 "
                            + context.quote().reservedCredit() + " 积分"
            );
        }
    }
}
