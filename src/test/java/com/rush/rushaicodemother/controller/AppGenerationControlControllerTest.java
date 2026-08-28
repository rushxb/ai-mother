package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.governance.app.AppGenerationControlPolicy;
import com.rush.rushaicodemother.orchestration.governance.app.AppGenerationControlService;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AppGenerationControlControllerTest {

    @Test
    void endpointsMustExposeVersionedFullReplacementContract() throws Exception {
        UserService userService = mock(UserService.class);
        AppGenerationControlService service = mock(AppGenerationControlService.class);
        User actor = User.builder().id(7L).build();
        when(userService.getLoginUser(any(HttpServletRequest.class))).thenReturn(actor);
        when(service.get(11L, actor)).thenReturn(AppGenerationControlPolicy.defaults(11L));
        when(service.update(eq(11L), any(), eq(actor))).thenReturn(persisted());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new AppGenerationControlController(userService, service)).build();

        mockMvc.perform(get("/generation/apps/11/control"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractVersion").value(1))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.inheritsTenantBudget").value(true));

        mockMvc.perform(put("/generation/apps/11/control")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedVersion": 0,
                                  "generationPaused": false,
                                  "emergencyStopped": true,
                                  "maxConcurrentTasks": 1,
                                  "modelPolicy": "ECONOMY_ONLY",
                                  "dependencyMutationPolicy": "DENY",
                                  "dependencyNetworkPolicy": "DENY",
                                  "dangerousToolPolicy": "DENY",
                                  "monthlyCreditLimit": 50
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.emergencyStopped").value(true))
                .andExpect(jsonPath("$.data.modelPolicy").value("ECONOMY_ONLY"))
                .andExpect(jsonPath("$.data.monthlyCreditLimit").value(50));
    }

    private AppGenerationControlPolicy persisted() {
        return new AppGenerationControlPolicy(
                11L, 1L, false, true, 1,
                AppGenerationControlPolicy.ModelPolicy.ECONOMY_ONLY,
                AppGenerationControlPolicy.DependencyMutationPolicy.DENY,
                AppGenerationControlPolicy.DependencyNetworkPolicy.DENY,
                AppGenerationControlPolicy.DangerousToolPolicy.DENY,
                50L, 7L, Instant.parse("2026-08-28T08:00:00Z"));
    }
}
