package com.rush.rushaicodemother.service.trace;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationModelCallStatus;
import com.rush.rushaicodemother.model.enums.GenerationModelUsageSource;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.service.trace.GenerationTracePersistenceService.ModelCallRecord;
import com.rush.rushaicodemother.service.trace.GenerationTracePersistenceService.NewBuildLog;
import com.rush.rushaicodemother.service.trace.GenerationTracePersistenceService.NewTask;
import com.rush.rushaicodemother.service.trace.GenerationTracePersistenceService.TaskRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultGenerationTraceServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final String CALL_ID = "c47e4463-57c6-4e72-8424-fd5c1873a64e";
    private static final String HASH = "a".repeat(64);

    private GenerationTracePersistenceService persistenceService;
    private DefaultGenerationTraceService service;

    @BeforeEach
    void setUp() {
        persistenceService = mock(GenerationTracePersistenceService.class);
        service = new DefaultGenerationTraceService(
                persistenceService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void startTaskMustValidateRequiredBusinessFields() {
        GenerationTaskStartCommand command = new GenerationTaskStartCommand(
                "task-1", 1L, 2L, CodeGenTypeEnum.HTML, null,
                "创建页面", null, true, "strict", "agent"
        );

        assertThrows(BusinessException.class, () -> service.startTask(command));
        verify(persistenceService, never()).insertTask(any());
    }

    @Test
    void repeatedStartWithSamePayloadMustBeIdempotent() {
        GenerationTaskStartCommand command = startCommand();
        when(persistenceService.insertTask(any())).thenReturn(false);
        when(persistenceService.findTaskByTaskId("task-1"))
                .thenReturn(taskRecord(GenerationTaskStatus.RUNNING));

        service.startTask(command);

        verify(persistenceService).findTaskByTaskId("task-1");
    }

    @Test
    void startTaskMustEnrichExistingRuntimeShell() {
        GenerationTaskStartCommand command = startCommand();
        when(persistenceService.insertTask(any())).thenReturn(false);
        when(persistenceService.findTaskByTaskId("task-1"))
                .thenReturn(runtimeShell(1L, 2L));
        when(persistenceService.enrichRuntimeTaskTrace(anyLong(), any(), any(), any()))
                .thenReturn(true);

        service.startTask(command);

        ArgumentCaptor<NewTask> taskCaptor = ArgumentCaptor.forClass(NewTask.class);
        verify(persistenceService).enrichRuntimeTaskTrace(
                eq(10L), taskCaptor.capture(), isNull(), any());
        assertEquals("vue_project", taskCaptor.getValue().targetCodeGenType());
        assertEquals("创建页面", taskCaptor.getValue().userPrompt());
        assertEquals("agent", taskCaptor.getValue().orchestrationMode());
    }

    @Test
    void runtimeShellOwnedByDifferentRequestMustBeRejected() {
        when(persistenceService.insertTask(any())).thenReturn(false);
        when(persistenceService.findTaskByTaskId("task-1"))
                .thenReturn(runtimeShell(99L, 2L));

        assertThrows(BusinessException.class, () -> service.startTask(startCommand()));

        verify(persistenceService, never()).enrichRuntimeTaskTrace(
                anyLong(), any(), any(), any());
    }

    @Test
    void concurrentRuntimeShellEnrichmentMustRereadAndAcceptCompletedPayload() {
        when(persistenceService.insertTask(any())).thenReturn(false);
        when(persistenceService.findTaskByTaskId("task-1"))
                .thenReturn(runtimeShell(1L, 2L), taskRecord(GenerationTaskStatus.RUNNING));
        when(persistenceService.enrichRuntimeTaskTrace(anyLong(), any(), any(), any()))
                .thenReturn(false);

        service.startTask(startCommand());

        verify(persistenceService, times(2)).findTaskByTaskId("task-1");
        verify(persistenceService).enrichRuntimeTaskTrace(anyLong(), any(), any(), any());
    }

    @Test
    void repeatedStartWithDifferentPayloadMustFailExplicitly() {
        when(persistenceService.insertTask(any())).thenReturn(false);
        when(persistenceService.findTaskByTaskId("task-1"))
                .thenReturn(taskRecord(GenerationTaskStatus.RUNNING));
        GenerationTaskStartCommand conflicting = new GenerationTaskStartCommand(
                "task-1", 1L, 2L, CodeGenTypeEnum.HTML, CodeGenTypeEnum.VUE_PROJECT,
                "不同提示词", "增强提示词", true, "strict", "agent"
        );

        assertThrows(BusinessException.class, () -> service.startTask(conflicting));
    }

    @Test
    void runningTaskMayTransitionRouteWithoutChangingTaskIdentityOrUserPrompt() {
        TaskRecord createTask = createTaskRecord(GenerationTaskStatus.RUNNING, "创建页面");
        when(persistenceService.insertTask(any())).thenReturn(false);
        when(persistenceService.findTaskByTaskId("task-1")).thenReturn(createTask);
        when(persistenceService.lockTaskByTaskId("task-1")).thenReturn(createTask);
        GenerationTaskStartCommand heavyCommand = heavyStartCommand("创建页面");

        GenerationTaskTraceStartResult result = service.startOrTransitionTask(heavyCommand);

        assertEquals(GenerationTaskTraceStartResult.TRANSITIONED, result);
        ArgumentCaptor<NewTask> taskCaptor = ArgumentCaptor.forClass(NewTask.class);
        verify(persistenceService).transitionRunningTaskTrace(
                eq(10L), taskCaptor.capture(), isNull(), eq(NOW_LOCAL));
        assertEquals("task-1", taskCaptor.getValue().taskId());
        assertEquals("创建页面", taskCaptor.getValue().userPrompt());
        assertEquals("heavy_generation", taskCaptor.getValue().orchestrationMode());
        assertEquals("expert", taskCaptor.getValue().qualityGate());
    }

    @Test
    void routeTransitionMustRejectDifferentPromptAndTerminalTask() {
        when(persistenceService.insertTask(any())).thenReturn(false);
        when(persistenceService.findTaskByTaskId("task-1"))
                .thenReturn(createTaskRecord(GenerationTaskStatus.RUNNING, "创建页面"));
        when(persistenceService.lockTaskByTaskId("task-1"))
                .thenReturn(createTaskRecord(GenerationTaskStatus.RUNNING, "创建页面"));

        assertThrows(BusinessException.class,
                () -> service.startOrTransitionTask(heavyStartCommand("不同提示词")));

        when(persistenceService.findTaskByTaskId("task-1"))
                .thenReturn(createTaskRecord(GenerationTaskStatus.FAILED, "创建页面"));
        when(persistenceService.lockTaskByTaskId("task-1"))
                .thenReturn(createTaskRecord(GenerationTaskStatus.FAILED, "创建页面"));
        assertThrows(BusinessException.class,
                () -> service.startOrTransitionTask(heavyStartCommand("创建页面")));
        verify(persistenceService, never()).transitionRunningTaskTrace(
                anyLong(), any(), any(), any());
    }

    @Test
    void completeTaskMustUsePersistedStartTimeToCalculateDuration() {
        when(persistenceService.lockTaskByTaskId("task-1"))
                .thenReturn(taskRecord(GenerationTaskStatus.RUNNING));

        service.completeTask("task-1", GenerationTaskStatus.SUCCESS, null);

        verify(persistenceService).completeRunningTask(
                10L, GenerationTaskStatus.SUCCESS, NOW_LOCAL, 10_000L, null, null
        );
    }

    @Test
    void completeTaskWithoutOutcomeQualityMustKeepUsingTheExistingCollaborationPath() {
        when(persistenceService.lockTaskByTaskId("task-1"))
                .thenReturn(taskRecord(GenerationTaskStatus.RUNNING));

        // 传入空证据等价于未采集，必须仍走既有 6 参数持久化调用，
        // 不得因新增能力改变既有调用契约。
        service.completeTask("task-1", GenerationTaskStatus.SUCCESS, null,
                GenerationOutcomeQuality.empty());

        verify(persistenceService).completeRunningTask(
                10L, GenerationTaskStatus.SUCCESS, NOW_LOCAL, 10_000L, null, null
        );
        verify(persistenceService, never()).completeRunningTask(
                anyLong(), any(), any(), anyLong(), any(), any(), any()
        );
    }

    @Test
    void completeTaskMustForwardCollectedOutcomeQualityToPersistence() {
        when(persistenceService.lockTaskByTaskId("task-1"))
                .thenReturn(taskRecord(GenerationTaskStatus.RUNNING));
        GenerationOutcomeQuality quality =
                GenerationOutcomeQuality.ofSuccess(3, 0, true, 8_000L);

        service.completeTask("task-1", GenerationTaskStatus.SUCCESS, null, quality);

        verify(persistenceService).completeRunningTask(
                10L, GenerationTaskStatus.SUCCESS, NOW_LOCAL, 10_000L, null, null, quality
        );
    }

    @Test
    void terminalTransitionMustBeIdempotentOnlyForSameStatus() {
        when(persistenceService.lockTaskByTaskId("task-1"))
                .thenReturn(taskRecord(GenerationTaskStatus.SUCCESS));

        service.completeTask("task-1", GenerationTaskStatus.SUCCESS, null);
        assertThrows(BusinessException.class,
                () -> service.completeTask("task-1", GenerationTaskStatus.FAILED, "失败"));

        verify(persistenceService, never()).completeRunningTask(
                anyLong(), any(), any(), anyLong(), any(), any()
        );
    }

    @Test
    void terminalTaskMustRejectStageUpdates() {
        when(persistenceService.lockTaskByTaskId("task-1"))
                .thenReturn(taskRecord(GenerationTaskStatus.CANCELLED));

        assertThrows(BusinessException.class,
                () -> service.updateStage("task-1", "build", "正在构建"));
        verify(persistenceService, never()).updateRunningTaskStage(
                anyLong(), any(), any(), any(), any()
        );
    }

    @Test
    void modelCallMustValidateUsageAndRemainIdempotentByCallId() {
        GenerationModelCallCommand command = modelCallCommand();
        when(persistenceService.insertModelCall(any())).thenReturn(false);
        when(persistenceService.findModelCallByCallId(CALL_ID))
                .thenReturn(modelCallRecord("gpt-test"));

        service.recordModelCall(command);

        GenerationModelCallCommand invalidTotal = new GenerationModelCallCommand(
                UUID.randomUUID().toString(), "task-1", 1L, 2L, "openai", "gpt-test",
                GenerationModelCallStatus.SUCCESS, "response-1",
                8, 5, 12, 125L, "STOP", GenerationModelUsageSource.OFFICIAL,
                null, provenance()
        );
        assertThrows(BusinessException.class, () -> service.recordModelCall(invalidTotal));
    }

    @Test
    void reusedCallIdWithDifferentPayloadMustFailExplicitly() {
        when(persistenceService.insertModelCall(any())).thenReturn(false);
        when(persistenceService.findModelCallByCallId(CALL_ID))
                .thenReturn(modelCallRecord("other-model"));

        assertThrows(BusinessException.class, () -> service.recordModelCall(modelCallCommand()));
    }

    @Test
    void failedModelCallMustAcceptUnavailableUsageWithoutFabricatedTokens() {
        when(persistenceService.insertModelCall(any())).thenReturn(true);
        GenerationModelCallCommand failed = new GenerationModelCallCommand(
                UUID.randomUUID().toString(), "task-1", 1L, 2L, "openai", "gpt-test",
                GenerationModelCallStatus.ERROR, null,
                null, null, null, 125L, null, GenerationModelUsageSource.UNAVAILABLE,
                "model_timeout", provenance()
        );

        service.recordModelCall(failed);

        ArgumentCaptor<GenerationTracePersistenceService.NewModelCall> captor =
                ArgumentCaptor.forClass(GenerationTracePersistenceService.NewModelCall.class);
        verify(persistenceService).insertModelCall(captor.capture());
        assertEquals(GenerationModelCallStatus.ERROR, captor.getValue().status());
        assertEquals(GenerationModelUsageSource.UNAVAILABLE, captor.getValue().usageSource());
        assertEquals(null, captor.getValue().totalTokens());
    }

    @Test
    void terminalModelCallMustCompleteTheDurableStartedLedgerInsteadOfConflicting() {
        GenerationModelCallCommand completed = modelCallCommand();
        when(persistenceService.insertModelCall(any())).thenReturn(false);
        when(persistenceService.findModelCallByCallId(CALL_ID))
                .thenReturn(startedModelCallRecord());

        service.recordModelCall(completed);

        ArgumentCaptor<GenerationTracePersistenceService.NewModelCall> captor =
                ArgumentCaptor.forClass(GenerationTracePersistenceService.NewModelCall.class);
        verify(persistenceService).completeStartedModelCall(captor.capture());
        assertEquals(GenerationModelCallStatus.SUCCESS, captor.getValue().status());
        assertEquals(13, captor.getValue().totalTokens());
    }

    @Test
    void buildResultEventMustMapTypedFieldsAndBoundReportLength() {
        String oversizedReport = "x".repeat(13_000);
        GenerationStreamEvent event = GenerationStreamEvent.buildResult("fallback", Map.of(
                "success", true,
                "stage", "build",
                "summary", "构建成功",
                "report", oversizedReport,
                "qualityGate", "strict",
                "willAutoRepair", false
        ));

        service.recordEvent("task-1", 1L, 2L, event);

        ArgumentCaptor<NewBuildLog> captor = ArgumentCaptor.forClass(NewBuildLog.class);
        verify(persistenceService).insertBuildLog(captor.capture());
        assertEquals("build", captor.getValue().stage());
        assertEquals(true, captor.getValue().success());
        assertEquals(12_000, captor.getValue().report().length());
        assertEquals(NOW_LOCAL, captor.getValue().createTime());
    }

    private GenerationTaskStartCommand startCommand() {
        return new GenerationTaskStartCommand(
                "task-1", 1L, 2L, CodeGenTypeEnum.HTML, CodeGenTypeEnum.VUE_PROJECT,
                "创建页面", "增强提示词", true, "strict", "agent"
        );
    }

    private GenerationTaskStartCommand heavyStartCommand(String userPrompt) {
        return new GenerationTaskStartCommand(
                "task-1", 1L, 2L, CodeGenTypeEnum.VUE_PROJECT, CodeGenTypeEnum.VUE_PROJECT,
                userPrompt, "重型专家增强提示词", true, "expert", "heavy_generation"
        );
    }

    private GenerationModelCallCommand modelCallCommand() {
        return new GenerationModelCallCommand(
                CALL_ID, "task-1", 1L, 2L, "openai", "gpt-test",
                GenerationModelCallStatus.SUCCESS, "response-1",
                8, 5, 13, 125L, "STOP", GenerationModelUsageSource.OFFICIAL,
                null, provenance()
        );
    }

    private ModelCallRecord modelCallRecord(String model) {
        return new ModelCallRecord(
                CALL_ID, "task-1", 1L, 2L, "openai", model,
                GenerationModelCallStatus.SUCCESS, "response-1",
                8, 5, 13, 125L, "STOP", GenerationModelUsageSource.OFFICIAL,
                null, HASH, HASH, HASH, HASH, 2, 3, "{}"
        );
    }

    private ModelCallRecord startedModelCallRecord() {
        return new ModelCallRecord(
                CALL_ID, "task-1", 1L, 2L, "openai", "gpt-test",
                GenerationModelCallStatus.STARTED, null,
                8, 0, 8, 0L, null, GenerationModelUsageSource.ESTIMATED,
                null, HASH, HASH, HASH, HASH, 2, 3, "{}"
        );
    }

    private GenerationModelCallProvenance provenance() {
        return new GenerationModelCallProvenance(HASH, HASH, HASH, HASH, 2, 3, "{}");
    }

    private TaskRecord runtimeShell(long appId, long userId) {
        return new TaskRecord(
                10L, "task-1", appId, userId, null, null, GenerationTaskStatus.RUNNING,
                "starting", null, null, null, false,
                null, null, NOW_LOCAL.minusSeconds(1), null, null,
                null, null, NOW_LOCAL.minusSeconds(1)
        );
    }

    private TaskRecord taskRecord(GenerationTaskStatus status) {
        return new TaskRecord(
                10L, "task-1", 1L, 2L, "html", "vue_project", status,
                "start", null, "创建页面", "增强提示词", true,
                "strict", "agent", NOW_LOCAL.minusSeconds(10),
                status.isTerminal() ? NOW_LOCAL : null,
                status.isTerminal() ? 10_000L : null,
                null, null, NOW_LOCAL.minusSeconds(10)
        );
    }

    private TaskRecord createTaskRecord(GenerationTaskStatus status, String userPrompt) {
        return new TaskRecord(
                10L, "task-1", 1L, 2L, "vue_project", "vue_project", status,
                "create", null, userPrompt, userPrompt, true,
                "create", "create", NOW_LOCAL.minusSeconds(10),
                status.isTerminal() ? NOW_LOCAL : null,
                status.isTerminal() ? 10_000L : null,
                null, null, NOW_LOCAL.minusSeconds(10)
        );
    }
}
