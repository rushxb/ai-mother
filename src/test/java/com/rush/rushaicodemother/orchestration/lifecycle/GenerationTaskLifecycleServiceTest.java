package com.rush.rushaicodemother.orchestration.lifecycle;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.trace.GenerationTaskStartCommand;
import com.rush.rushaicodemother.service.trace.GenerationTaskTraceStartResult;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskLifecycleServiceTest {

    private GenerationAppStateService appStateService;
    private ChatHistoryService chatHistoryService;
    private GenerationTraceService traceService;
    private UserCreditService creditService;
    private GenerationTaskLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        appStateService = mock(GenerationAppStateService.class);
        chatHistoryService = mock(ChatHistoryService.class);
        traceService = mock(GenerationTraceService.class);
        creditService = mock(UserCreditService.class);
        lifecycleService = new GenerationTaskLifecycleService(
                appStateService, chatHistoryService, traceService, creditService);
    }

    @Test
    void startGenerationMustClaimStateAndCreateTraceWithOneTaskIdentity() {
        App app = new App();
        app.setId(11L);
        User user = new User();
        user.setId(22L);

        lifecycleService.startGeneration(
                "task-1", app, user,
                CodeGenTypeEnum.HTML, CodeGenTypeEnum.VUE_PROJECT,
                "prompt", "enhanced", true, "strict", "heavy", "create");

        verify(appStateService).claimGenerationState(
                11L, "task-1", "create", CodeGenTypeEnum.VUE_PROJECT);
        ArgumentCaptor<GenerationTaskStartCommand> commandCaptor =
                ArgumentCaptor.forClass(GenerationTaskStartCommand.class);
        verify(traceService).startTask(commandCaptor.capture());
        assertEquals("task-1", commandCaptor.getValue().taskId());
        assertEquals(11L, commandCaptor.getValue().appId());
        assertEquals(22L, commandCaptor.getValue().userId());
    }

    @Test
    void stageUpdateMustUseSameOwnerForApplicationStateAndTrace() {
        lifecycleService.updateGenerationStage("task-1", 11L, "build", "building");

        verify(appStateService).updateOwnedGenerationStage(
                11L, "task-1", "build", "building");
        verify(traceService).updateStage("task-1", "build", "building");
    }

    @Test
    void routeTransitionMustNotRecordTheUserMessageAgain() {
        when(traceService.startOrTransitionTask(org.mockito.ArgumentMatchers.any()))
                .thenReturn(GenerationTaskTraceStartResult.TRANSITIONED);

        lifecycleService.startOrTransitionGeneration(
                "task-1", 11L, 22L,
                CodeGenTypeEnum.VUE_PROJECT, CodeGenTypeEnum.VUE_PROJECT,
                "创建页面", "专家增强提示词", true, "expert",
                "heavy_generation", "agent");

        verify(appStateService).claimGenerationState(
                11L, "task-1", "agent", CodeGenTypeEnum.VUE_PROJECT);
        verify(traceService).startOrTransitionTask(org.mockito.ArgumentMatchers.any());
        verify(chatHistoryService, never()).addChatMessage(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void firstTraceStartMustRecordExactlyOneUserMessage() {
        when(traceService.startOrTransitionTask(org.mockito.ArgumentMatchers.any()))
                .thenReturn(GenerationTaskTraceStartResult.STARTED);

        lifecycleService.startOrTransitionGeneration(
                "task-1", 11L, 22L,
                CodeGenTypeEnum.VUE_PROJECT, CodeGenTypeEnum.VUE_PROJECT,
                "创建页面", "创建页面", true, "create", "create", "create");

        verify(chatHistoryService).addChatMessage(
                11L, "创建页面",
                com.rush.rushaicodemother.model.enums.ChatHistoryMessageTypeEnum.USER.getValue(), 22L);
    }

    @Test
    void finalizationMustFinishTraceAndSettleOnceWhenStateWasTakenByANewerTask() {
        when(appStateService.releaseOwnedGenerationState(11L, "task-1", 7L)).thenReturn(false);

        boolean released = lifecycleService.finalizeGeneration(
                "task-1", 11L, 7L, GenerationTaskStatus.SUCCESS,
                null, null, null);

        assertFalse(released);
        verify(appStateService).lockGenerationState(11L);
        verify(traceService).completeTask("task-1", GenerationTaskStatus.SUCCESS, null);
        verify(creditService).chargeGenerationTask("task-1");
    }

    @Test
    void finalizationMustPersistMemorySummaryBeforeTerminalStateAndSettlement() {
        lifecycleService.finalizeGeneration(
                "task-failed",
                11L,
                3L,
                GenerationTaskStatus.FAILED,
                "build_failed",
                "任务状态：失败\n失败原因：构建验证未通过",
                null
        );

        InOrder traceOrder = org.mockito.Mockito.inOrder(traceService, appStateService, creditService);
        traceOrder.verify(appStateService).lockGenerationState(11L);
        traceOrder.verify(traceService).updateMemorySummary(
                "task-failed", "任务状态：失败\n失败原因：构建验证未通过");
        traceOrder.verify(appStateService).releaseOwnedGenerationState(11L, "task-failed", 3L);
        traceOrder.verify(traceService).completeTask(
                "task-failed", GenerationTaskStatus.FAILED, "build_failed");
        traceOrder.verify(creditService).chargeGenerationTask("task-failed");
    }

    @Test
    void databaseLifecycleMethodsMustDeclareRollbackForException() throws Exception {
        assertTransactional("startGeneration",
                String.class, Long.class, Long.class, CodeGenTypeEnum.class, CodeGenTypeEnum.class,
                String.class, String.class, boolean.class, String.class, String.class, String.class);
        assertTransactional("startOrTransitionGeneration",
                String.class, Long.class, Long.class, CodeGenTypeEnum.class, CodeGenTypeEnum.class,
                String.class, String.class, boolean.class, String.class, String.class, String.class);
        assertTransactional("updateGenerationStage",
                String.class, Long.class, String.class, String.class);
        assertTransactional("finalizeGeneration",
                String.class, Long.class, Long.class, GenerationTaskStatus.class,
                String.class, String.class,
                com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality.class);
    }

    private void assertTransactional(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = GenerationTaskLifecycleService.class.getMethod(methodName, parameterTypes);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional, methodName + " must define a transaction boundary");
        assertTrue(Arrays.asList(transactional.rollbackFor()).contains(Exception.class));
    }
}
