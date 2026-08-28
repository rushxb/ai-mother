package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.governance.TenantGenerationControlPlaneService;
import com.rush.rushaicodemother.orchestration.governance.TenantGenerationControlPlaneSnapshot;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TenantGenerationControlPlaneControllerTest {

    @Test
    void endpointMustReturnVersionedLowSensitivityTenantSummary() throws Exception {
        UserService userService = mock(UserService.class);
        TenantGenerationControlPlaneService service = mock(TenantGenerationControlPlaneService.class);
        User actor = User.builder().id(7L).build();
        when(userService.getLoginUser(any(HttpServletRequest.class))).thenReturn(actor);
        when(service.get(100L, actor)).thenReturn(snapshot());
        TenantGenerationControlPlaneController controller =
                new TenantGenerationControlPlaneController(userService, service);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/generation/tenants/100/control-plane"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractVersion").value(1))
                .andExpect(jsonPath("$.data.tenantId").value(100))
                .andExpect(jsonPath("$.data.budget.monthlyCreditLimit").value(10000))
                .andExpect(jsonPath("$.data.budget.remainingCredit").value(9100))
                .andExpect(jsonPath("$.data.queue.queuedTasks").value(2))
                .andExpect(jsonPath("$.data.queue.remainingNonTerminalSlots").value(10))
                .andExpect(jsonPath("$.data.scenarioCosts[0].route").value("heavy_generation"))
                .andExpect(jsonPath("$.data.scenarioCosts[0].unitSuccessfulCreditCost").value(3.50))
                .andExpect(jsonPath("$.data.activeRejectionReasons[0].code")
                        .value("tenant_heavy_capacity_reached"))
                .andExpect(jsonPath("$.data.taskId").doesNotExist())
                .andExpect(jsonPath("$.data.userId").doesNotExist());

        verify(service).get(100L, actor);
    }

    private TenantGenerationControlPlaneSnapshot snapshot() {
        return new TenantGenerationControlPlaneSnapshot(
                100L,
                Instant.parse("2026-08-28T04:00:00Z"),
                new TenantGenerationControlPlaneSnapshot.BudgetSummary(
                        Instant.parse("2026-07-31T16:00:00Z"),
                        Instant.parse("2026-08-31T16:00:00Z"),
                        10_000L, 900L, 9_100L),
                new TenantGenerationControlPlaneSnapshot.QueueSummary(
                        2, 3, 1, 6, 4, 16, 4, 10, 0),
                List.of(new TenantGenerationControlPlaneSnapshot.ScenarioCostSummary(
                        "heavy_generation", "multi_file", 3L, 2L, 7L,
                        new BigDecimal("3.50"))),
                List.of(new TenantGenerationControlPlaneSnapshot.AdmissionBlocker(
                        "tenant_heavy_capacity_reached",
                        "当前租户同时进行中的专家生成任务已达到上限（4）"))
        );
    }
}
