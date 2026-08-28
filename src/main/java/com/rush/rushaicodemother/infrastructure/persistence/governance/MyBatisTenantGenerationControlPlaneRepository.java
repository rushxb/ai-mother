package com.rush.rushaicodemother.infrastructure.persistence.governance;

import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.mapper.TenantGenerationControlPlaneMapper;
import com.rush.rushaicodemother.mapper.TenantGenerationQueueRow;
import com.rush.rushaicodemother.mapper.TenantGenerationScenarioCostRow;
import com.rush.rushaicodemother.orchestration.governance.TenantGenerationControlPlaneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 基于 MySQL 结构化事实的租户生成控制面只读适配器。 */
@Repository
@RequiredArgsConstructor
public class MyBatisTenantGenerationControlPlaneRepository
        implements TenantGenerationControlPlaneRepository {

    private final GenerationTaskRuntimeMapper runtimeMapper;
    private final TenantGenerationControlPlaneMapper controlPlaneMapper;

    @Override
    @Transactional(readOnly = true)
    public ControlPlaneFacts load(Long tenantId,
                                  LocalDateTime periodStart,
                                  LocalDateTime observedBefore) {
        requireValidScope(tenantId, periodStart, observedBefore);
        long consumedCredit = runtimeMapper.sumTenantGenerationCreditUsage(
                tenantId, periodStart, observedBefore);
        TenantGenerationQueueRow queue = controlPlaneMapper.selectQueueSummary(tenantId);
        if (queue == null) {
            throw new IllegalStateException("租户生成控制面缺少排队统计结果");
        }
        List<TenantGenerationScenarioCostRow> scenarioRows =
                controlPlaneMapper.selectScenarioCosts(tenantId, periodStart, observedBefore);
        if (scenarioRows == null) {
            throw new IllegalStateException("租户生成控制面缺少场景成本统计结果");
        }
        return new ControlPlaneFacts(
                consumedCredit,
                new QueueFacts(
                        queue.queuedTasks(),
                        queue.runningTasks(),
                        queue.waitingApprovalTasks(),
                        queue.totalNonTerminalTasks(),
                        queue.heavyNonTerminalTasks()),
                scenarioRows.stream().map(this::toFacts).toList()
        );
    }

    private ScenarioCostFacts toFacts(TenantGenerationScenarioCostRow row) {
        if (row == null || row.settledTasks() == null
                || row.successfulDeliveries() == null || row.totalCreditCost() == null) {
            throw new IllegalStateException("租户生成控制面场景成本行不完整");
        }
        return new ScenarioCostFacts(
                row.route(), row.targetCodeGenType(),
                row.settledTasks(), row.successfulDeliveries(), row.totalCreditCost());
    }

    private void requireValidScope(Long tenantId,
                                   LocalDateTime periodStart,
                                   LocalDateTime observedBefore) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenantId 必须为正数");
        }
        if (periodStart == null || observedBefore == null
                || !periodStart.isBefore(observedBefore)) {
            throw new IllegalArgumentException("租户生成控制面观察窗口无效");
        }
    }
}
