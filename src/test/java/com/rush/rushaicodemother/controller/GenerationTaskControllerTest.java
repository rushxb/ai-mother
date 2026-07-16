package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.controller.support.GenerationSseEventMapper;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.GenerationTaskStatusVO;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskControlService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskQueryService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSnapshot;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.service.AppService;
import com.rush.rushaicodemother.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GenerationTaskControllerTest {

    private AppService appService;
    private UserService userService;
    private GenerationTaskQueryService queryService;
    private GenerationTaskControlService controlService;
    private GenerationTaskController controller;
    private User actor;

    @BeforeEach
    void setUp() {
        appService = mock(AppService.class);
        userService = mock(UserService.class);
        queryService = mock(GenerationTaskQueryService.class);
        controlService = mock(GenerationTaskControlService.class);
        controller = new GenerationTaskController(
                appService, userService, queryService, controlService, new GenerationSseEventMapper());
        actor = User.builder().id(7L).build();
        when(userService.getLoginUser(any(HttpServletRequest.class))).thenReturn(actor);
    }

    @Test
    void submitMustReturnAcceptedTaskIdentityWithoutWaitingForEventStream() throws Exception {
        GenerationTaskSnapshot snapshot = snapshot("running", false);
        when(appService.submitGeneration(eq(11L), eq("build dashboard"), same(actor)))
                .thenReturn(new GenerationTaskResult(
                        "task-api-1", "lightweight_edit", mock(GenerationWorkspace.class), Flux.never()));
        when(queryService.get("task-api-1", actor)).thenReturn(snapshot);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/generation/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "appId": 11,
                                  "message": "build dashboard"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.taskId").value("task-api-1"))
                .andExpect(jsonPath("$.data.status").value("running"));

        verify(appService).submitGeneration(11L, "build dashboard", actor);
        verify(queryService).get("task-api-1", actor);
    }

    @Test
    void getAndCancelMustDelegateThroughTaskScopedAuthorizationServices() throws Exception {
        GenerationTaskSnapshot running = snapshot("running", false);
        GenerationTaskSnapshot cancelling = snapshot("cancelling", true);
        when(queryService.get("task-api-1", actor)).thenReturn(running);
        when(controlService.cancel("task-api-1", actor)).thenReturn(cancelling);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/generation/tasks/task-api-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("running"));
        mockMvc.perform(post("/generation/tasks/task-api-1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("cancelling"))
                .andExpect(jsonPath("$.data.cancellationRequested").value(true));

        verify(queryService).get("task-api-1", actor);
        verify(controlService).cancel("task-api-1", actor);
    }

    @Test
    void eventsMustUseSharedSseMappingAndAppendDoneEvent() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(userService.getLoginUser(request)).thenReturn(actor);
        when(queryService.events("task-api-1", actor))
                .thenReturn(Flux.just(GenerationStreamEvent.aiDelta("hello")));

        List<ServerSentEvent<String>> events = controller.events("task-api-1", request)
                .collectList()
                .block(Duration.ofSeconds(1));

        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals(GenerationStreamEvent.AI_DELTA, events.getFirst().event());
        assertEquals("done", events.getLast().event());
        verify(queryService).events("task-api-1", actor);
    }

    @Test
    void cancelResponseMustExposeTaskSnapshot() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(userService.getLoginUser(request)).thenReturn(actor);
        when(controlService.cancel("task-api-1", actor)).thenReturn(snapshot("cancelling", true));

        BaseResponse<GenerationTaskStatusVO> response = controller.cancel("task-api-1", request);

        assertEquals("task-api-1", response.getData().taskId());
        assertEquals("cancelling", response.getData().status());
    }

    private GenerationTaskSnapshot snapshot(String status, boolean cancellationRequested) {
        Instant submittedAt = Instant.parse("2026-07-15T10:00:00Z");
        return new GenerationTaskSnapshot(
                "task-api-1",
                11L,
                actor.getId(),
                "lightweight_edit",
                status,
                "build",
                "正在构建",
                submittedAt,
                submittedAt.plusSeconds(1200),
                cancellationRequested,
                cancellationRequested ? "user_requested" : null,
                Map.of(),
                Map.of(),
                null
        );
    }
}
