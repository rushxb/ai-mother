package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.common.BaseResponse;
import com.rush.rushaicodemother.config.GenerationSseProperties;
import com.rush.rushaicodemother.controller.support.GenerationSseEventMapper;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.model.vo.GenerationTaskStatusVO;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.eventstream.SequencedGenerationEvent;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskControlService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskQueryService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSnapshot;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSubmissionReceipt;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.tool.DestructiveToolAction;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalService;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolContinuationScheduler;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalRecord;
import com.rush.rushaicodemother.service.AppService;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.feedback.GenerationFeedbackService;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private ToolApprovalService toolApprovalService;
    private GenerationToolContinuationScheduler toolContinuationScheduler;
    private GenerationFeedbackService generationFeedbackService;
    private User actor;

    @BeforeEach
    void setUp() {
        appService = mock(AppService.class);
        userService = mock(UserService.class);
        queryService = mock(GenerationTaskQueryService.class);
        controlService = mock(GenerationTaskControlService.class);
        toolApprovalService = mock(ToolApprovalService.class);
        toolContinuationScheduler = mock(GenerationToolContinuationScheduler.class);
        generationFeedbackService = mock(GenerationFeedbackService.class);
        GenerationSseProperties sseProperties = new GenerationSseProperties();
        controller = new GenerationTaskController(
                appService, userService, queryService, controlService,
                new GenerationSseEventMapper(sseProperties), toolApprovalService,
                toolContinuationScheduler, generationFeedbackService);
        actor = User.builder().id(7L).build();
        when(userService.getLoginUser(any(HttpServletRequest.class))).thenReturn(actor);
    }

    @Test
    void submitMustReturnAcceptedTaskIdentityWithoutWaitingForEventStream() throws Exception {
        Instant submittedAt = Instant.parse("2026-07-20T10:00:00Z");
        when(appService.submitGeneration(
                eq(11L), eq("build dashboard"), same(actor), eq("submission-key")))
                .thenReturn(new GenerationTaskResult(
                        new GenerationTaskSubmissionReceipt(
                                "task-api-1",
                                11L,
                                "lightweight_edit",
                                GenerationTaskStatus.QUEUED,
                                submittedAt,
                                submittedAt.plusSeconds(600)
                        ),
                        mock(GenerationWorkspace.class),
                        Flux.never()
                ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/generation/tasks")
                        .header("Idempotency-Key", "submission-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "appId": 11,
                                  "message": "build dashboard"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.taskId").value("task-api-1"))
                .andExpect(jsonPath("$.data.status").value("queued"))
                .andExpect(jsonPath("$.data.submittedAt").value("2026-07-20T10:00:00Z"))
                .andExpect(jsonPath("$.data.deadlineAt").value("2026-07-20T10:10:00Z"));

        verify(appService).submitGeneration(11L, "build dashboard", actor, "submission-key");
        verifyNoInteractions(queryService);
    }

    @Test
    void destructiveToolApprovalMustBeTaskScopedAndOwnershipChecked() throws Exception {
        when(queryService.get("task-api-1", actor)).thenReturn(snapshot("running", false));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        String approvalId = "a".repeat(64);
        ToolApprovalRecord decision = mock(ToolApprovalRecord.class);
        when(toolApprovalService.approve(
                "task-api-1", DestructiveToolAction.SNAPSHOT_ROLLBACK,
                approvalId, actor.getId())).thenReturn(decision);

        mockMvc.perform(post("/generation/tasks/task-api-1/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"rollbackSnapshot\",\"approvalId\":\""
                                + approvalId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        verify(queryService).get("task-api-1", actor);
        verify(toolApprovalService).approve(
                "task-api-1", DestructiveToolAction.SNAPSHOT_ROLLBACK, approvalId, actor.getId());
        verify(toolContinuationScheduler).schedule(decision);
    }

    @Test
    void destructiveToolApprovalMayBeRejectedByTheProjectOwner() throws Exception {
        when(queryService.get("task-api-1", actor)).thenReturn(snapshot("running", false));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        String approvalId = "b".repeat(64);
        ToolApprovalRecord decision = mock(ToolApprovalRecord.class);
        when(toolApprovalService.reject(
                "task-api-1", DestructiveToolAction.SNAPSHOT_DELETE,
                approvalId, actor.getId())).thenReturn(decision);

        mockMvc.perform(post("/generation/tasks/task-api-1/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"deleteSnapshot\",\"approvalId\":\""
                                + approvalId + "\",\"decision\":\"reject\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));

        verify(toolApprovalService).reject(
                "task-api-1", DestructiveToolAction.SNAPSHOT_DELETE, approvalId, actor.getId());
        verify(toolContinuationScheduler).schedule(decision);
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
    void activeTaskLookupMustExposeResumeIdentityForApplication() throws Exception {
        when(queryService.findLatestNonTerminalForApp(11L, actor))
                .thenReturn(Optional.of(snapshot("running", false)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/generation/tasks/by-app/11/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value("task-api-1"));

        verify(queryService).findLatestNonTerminalForApp(11L, actor);
    }

    @Test
    void eventsMustResumeFromNewestCursorAndExposeSequencedSseIds() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(userService.getLoginUser(request)).thenReturn(actor);
        when(queryService.sequencedEvents("task-api-1", 7L, actor))
                .thenReturn(Flux.just(
                        SequencedGenerationEvent.event(8L, GenerationStreamEvent.aiDelta("hello")),
                        SequencedGenerationEvent.complete(9L)
                ));

        List<ServerSentEvent<String>> events = controller.events("task-api-1", 5L, "7", request)
                .collectList()
                .block(Duration.ofSeconds(1));

        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals("8", events.getFirst().id());
        assertEquals(GenerationStreamEvent.AI_DELTA, events.getFirst().event());
        assertEquals("9", events.getLast().id());
        assertEquals("done", events.getLast().event());
        verify(queryService).sequencedEvents("task-api-1", 7L, actor);
    }

    @Test
    void eventsMustRejectMalformedLastEventId() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(userService.getLoginUser(request)).thenReturn(actor);

        assertThrows(BusinessException.class,
                () -> controller.events("task-api-1", null, "not-a-sequence", request));
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
