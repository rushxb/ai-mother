package com.rush.rushaicodemother.orchestration.lifecycle;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.trace.GenerationTaskStartCommand;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskLifecycleServiceTest {

    private GenerationAppStateService appStateService;
    private GenerationTraceService traceService;
    private UserCreditService creditService;
    private GenerationTaskLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        appStateService = mock(GenerationAppStateService.class);
        traceService = mock(GenerationTraceService.class);
        creditService = mock(UserCreditService.class);
        lifecycleService = new GenerationTaskLifecycleService(
                appStateService, mock(ChatHistoryService.class), traceService, creditService);
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
    void completionMustFinishTraceEvenWhenStateWasAlreadyTakenByANewerTask() {
        when(appStateService.releaseOwnedGenerationState(11L, "task-1")).thenReturn(false);

        boolean released = lifecycleService.completeGenerationAndCharge(
                "task-1", 11L, GenerationTaskStatus.SUCCESS, null);

        assertFalse(released);
        verify(traceService).completeTask("task-1", GenerationTaskStatus.SUCCESS, null);
        verify(creditService).chargeGenerationTask("task-1");
    }

    @Test
    void databaseLifecycleMethodsMustDeclareRollbackForException() throws Exception {
        assertTransactional("startGeneration",
                String.class, Long.class, Long.class, CodeGenTypeEnum.class, CodeGenTypeEnum.class,
                String.class, String.class, boolean.class, String.class, String.class, String.class);
        assertTransactional("updateGenerationStage",
                String.class, Long.class, String.class, String.class);
        assertTransactional("completeGeneration",
                String.class, Long.class, GenerationTaskStatus.class, String.class);
        assertTransactional("completeGenerationAndCharge",
                String.class, Long.class, GenerationTaskStatus.class, String.class);
        assertTransactional("completeGenerationAndCharge",
                String.class, Long.class, GenerationTaskStatus.class, String.class, String.class);
    }

    private void assertTransactional(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = GenerationTaskLifecycleService.class.getMethod(methodName, parameterTypes);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional, methodName + " must define a transaction boundary");
        assertTrue(Arrays.asList(transactional.rollbackFor()).contains(Exception.class));
    }
}
