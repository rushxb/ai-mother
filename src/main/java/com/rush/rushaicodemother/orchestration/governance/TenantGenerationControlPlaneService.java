package com.rush.rushaicodemother.orchestration.governance;

import com.rush.rushaicodemother.config.GenerationTaskAdmissionProperties;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.TenantRole;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/** 组装租户管理员只读控制面，并保证授权先于任何租户级聚合。 */
@Service
public class TenantGenerationControlPlaneService {

    private static final String ACCESS_DENIED_MESSAGE = "仅租户管理员可查看租户生成控制面";

    private final TenantAuthorizationService authorizationService;
    private final TenantGenerationControlPlaneRepository repository;
    private final GenerationTaskAdmissionProperties properties;
    private final Clock clock;

    @Autowired
    public TenantGenerationControlPlaneService(
            TenantAuthorizationService authorizationService,
            TenantGenerationControlPlaneRepository repository,
            GenerationTaskAdmissionProperties properties) {
        this(authorizationService, repository, properties, Clock.systemDefaultZone());
    }

    public TenantGenerationControlPlaneService(
            TenantAuthorizationService authorizationService,
            TenantGenerationControlPlaneRepository repository,
            GenerationTaskAdmissionProperties properties,
            Clock clock) {
        this.authorizationService = authorizationService;
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 返回当前租户控制面快照。普通成员会在读取任何租户聚合前被拒绝。
     *
     * @param tenantId 租户编号
     * @param actor 当前登录用户
     * @return 只读控制面快照
     */
    @Transactional(readOnly = true)
    public TenantGenerationControlPlaneSnapshot get(Long tenantId, User actor) {
        Long actorId = actor == null ? null : actor.getId();
        authorizationService.requireRole(
                tenantId, actorId, TenantRole.ADMIN, ACCESS_DENIED_MESSAGE);

        ZonedDateTime observedAt = clock.instant().atZone(clock.getZone());
        ZonedDateTime periodStart = observedAt.withDayOfMonth(1).toLocalDate()
                .atStartOfDay(clock.getZone());
        ZonedDateTime periodEnd = periodStart.plusMonths(1);
        TenantGenerationControlPlaneRepository.ControlPlaneFacts facts = repository.load(
                tenantId,
                periodStart.toLocalDateTime(),
                observedAt.toLocalDateTime()
        );

        long monthlyLimit = properties.getMonthlyCreditLimitPerTenant();
        long remainingCredit = Math.max(0L, monthlyLimit - facts.consumedCredit());
        int taskLimit = properties.getMaxNonTerminalTasksPerTenant();
        int heavyLimit = properties.getMaxHeavyTasksPerTenant();
        TenantGenerationControlPlaneRepository.QueueFacts queueFacts = facts.queue();

        return new TenantGenerationControlPlaneSnapshot(
                tenantId,
                observedAt.toInstant(),
                new TenantGenerationControlPlaneSnapshot.BudgetSummary(
                        periodStart.toInstant(), periodEnd.toInstant(), monthlyLimit,
                        facts.consumedCredit(), remainingCredit),
                new TenantGenerationControlPlaneSnapshot.QueueSummary(
                        queueFacts.queuedTasks(),
                        queueFacts.runningTasks(),
                        queueFacts.waitingApprovalTasks(),
                        queueFacts.totalNonTerminalTasks(),
                        queueFacts.heavyNonTerminalTasks(),
                        taskLimit,
                        heavyLimit,
                        Math.max(0, taskLimit - queueFacts.totalNonTerminalTasks()),
                        Math.max(0, heavyLimit - queueFacts.heavyNonTerminalTasks())),
                facts.scenarioCosts().stream().map(this::toScenarioCostSummary).toList(),
                activeBlockers(facts, taskLimit, heavyLimit, monthlyLimit)
        );
    }

    private TenantGenerationControlPlaneSnapshot.ScenarioCostSummary toScenarioCostSummary(
            TenantGenerationControlPlaneRepository.ScenarioCostFacts facts) {
        BigDecimal unitCost = BigDecimal.valueOf(facts.totalCreditCost())
                .divide(BigDecimal.valueOf(facts.successfulDeliveries()), 2, RoundingMode.HALF_UP);
        return new TenantGenerationControlPlaneSnapshot.ScenarioCostSummary(
                facts.route(),
                facts.targetCodeGenType(),
                facts.settledTasks(),
                facts.successfulDeliveries(),
                facts.totalCreditCost(),
                unitCost
        );
    }

    private List<TenantGenerationControlPlaneSnapshot.AdmissionBlocker> activeBlockers(
            TenantGenerationControlPlaneRepository.ControlPlaneFacts facts,
            int taskLimit,
            int heavyLimit,
            long monthlyLimit) {
        List<TenantGenerationControlPlaneSnapshot.AdmissionBlocker> blockers = new ArrayList<>();
        if (facts.queue().totalNonTerminalTasks() >= taskLimit) {
            blockers.add(new TenantGenerationControlPlaneSnapshot.AdmissionBlocker(
                    "tenant_task_capacity_reached",
                    "当前租户同时进行中的生成任务已达到上限（" + taskLimit + "）"));
        }
        if (facts.queue().heavyNonTerminalTasks() >= heavyLimit) {
            blockers.add(new TenantGenerationControlPlaneSnapshot.AdmissionBlocker(
                    "tenant_heavy_capacity_reached",
                    "当前租户同时进行中的专家生成任务已达到上限（" + heavyLimit + "）"));
        }
        if (facts.consumedCredit() >= monthlyLimit) {
            blockers.add(new TenantGenerationControlPlaneSnapshot.AdmissionBlocker(
                    "monthly_budget_exhausted",
                    "当前租户本月生成预算已用尽"));
        }
        return List.copyOf(blockers);
    }
}
