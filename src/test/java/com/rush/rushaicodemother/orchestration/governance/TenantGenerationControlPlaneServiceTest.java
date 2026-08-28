package com.rush.rushaicodemother.orchestration.governance;

import com.rush.rushaicodemother.config.GenerationTaskAdmissionProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.TenantRole;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantGenerationControlPlaneServiceTest {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-08-28T04:00:00Z");

    private TenantAuthorizationService authorizationService;
    private TenantGenerationControlPlaneRepository repository;
    private GenerationTaskAdmissionProperties properties;
    private TenantGenerationControlPlaneService service;

    @BeforeEach
    void setUp() {
        authorizationService = mock(TenantAuthorizationService.class);
        repository = mock(TenantGenerationControlPlaneRepository.class);
        properties = new GenerationTaskAdmissionProperties();
        service = new TenantGenerationControlPlaneService(
                authorizationService,
                repository,
                properties,
                Clock.fixed(NOW, DATABASE_ZONE)
        );
    }

    @Test
    void administratorSnapshotMustExposeCurrentBudgetQueueScenarioCostsAndBlockers() {
        LocalDateTime periodStart = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime observedBefore = LocalDateTime.of(2026, 8, 28, 12, 0);
        when(repository.load(100L, periodStart, observedBefore))
                .thenReturn(new TenantGenerationControlPlaneRepository.ControlPlaneFacts(
                        10_500L,
                        new TenantGenerationControlPlaneRepository.QueueFacts(7, 5, 4, 16, 4),
                        List.of(
                                new TenantGenerationControlPlaneRepository.ScenarioCostFacts(
                                        "heavy_generation", "multi_file", 5, 3, 10),
                                new TenantGenerationControlPlaneRepository.ScenarioCostFacts(
                                        "lightweight_edit", "single_file", 4, 4, 10)
                        )
                ));

        TenantGenerationControlPlaneSnapshot snapshot = service.get(100L, User.builder().id(7L).build());

        assertEquals(100L, snapshot.tenantId());
        assertEquals(NOW, snapshot.observedAt());
        assertEquals(Instant.parse("2026-07-31T16:00:00Z"), snapshot.budget().periodStart());
        assertEquals(Instant.parse("2026-08-31T16:00:00Z"), snapshot.budget().periodEnd());
        assertEquals(10_000L, snapshot.budget().monthlyCreditLimit());
        assertEquals(10_500L, snapshot.budget().consumedCredit());
        assertEquals(0L, snapshot.budget().remainingCredit());
        assertEquals(7, snapshot.queue().queuedTasks());
        assertEquals(5, snapshot.queue().runningTasks());
        assertEquals(4, snapshot.queue().waitingApprovalTasks());
        assertEquals(16, snapshot.queue().totalNonTerminalTasks());
        assertEquals(4, snapshot.queue().heavyNonTerminalTasks());
        assertEquals(16, snapshot.queue().maxNonTerminalTasks());
        assertEquals(4, snapshot.queue().maxHeavyTasks());
        assertEquals(new BigDecimal("3.33"), snapshot.scenarioCosts().getFirst().unitSuccessfulCreditCost());
        assertEquals(new BigDecimal("2.50"), snapshot.scenarioCosts().getLast().unitSuccessfulCreditCost());
        assertEquals(List.of(
                        "tenant_task_capacity_reached",
                        "tenant_heavy_capacity_reached",
                        "monthly_budget_exhausted"),
                snapshot.activeRejectionReasons().stream()
                        .map(TenantGenerationControlPlaneSnapshot.AdmissionBlocker::code)
                        .toList());

        InOrder order = inOrder(authorizationService, repository);
        order.verify(authorizationService).requireRole(
                100L, 7L, TenantRole.ADMIN, "仅租户管理员可查看租户生成控制面");
        order.verify(repository).load(100L, periodStart, observedBefore);
    }

    @Test
    void ordinaryMemberMustBeRejectedBeforeAnyTenantWideAggregation() {
        doThrow(new BusinessException(40101, "denied"))
                .when(authorizationService)
                .requireRole(100L, 8L, TenantRole.ADMIN, "仅租户管理员可查看租户生成控制面");

        assertThrows(BusinessException.class,
                () -> service.get(100L, User.builder().id(8L).build()));

        verifyNoInteractions(repository);
    }

    @Test
    void availableCapacityMustProduceNoSyntheticRejectionHistory() {
        when(repository.load(100L, LocalDateTime.of(2026, 8, 1, 0, 0),
                LocalDateTime.of(2026, 8, 28, 12, 0)))
                .thenReturn(new TenantGenerationControlPlaneRepository.ControlPlaneFacts(
                        300L,
                        new TenantGenerationControlPlaneRepository.QueueFacts(1, 2, 0, 3, 1),
                        List.of()
                ));

        TenantGenerationControlPlaneSnapshot snapshot = service.get(100L, User.builder().id(7L).build());

        assertEquals(9_700L, snapshot.budget().remainingCredit());
        assertEquals(13, snapshot.queue().remainingNonTerminalSlots());
        assertEquals(3, snapshot.queue().remainingHeavySlots());
        assertEquals(List.of(), snapshot.activeRejectionReasons());
    }
}
