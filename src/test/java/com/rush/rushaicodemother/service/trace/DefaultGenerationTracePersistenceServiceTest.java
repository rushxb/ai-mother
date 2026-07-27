package com.rush.rushaicodemother.service.trace;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.mapper.GenerationTraceMapper;
import com.rush.rushaicodemother.model.entity.GenerationModelCall;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.GenerationModelCallStatus;
import com.rush.rushaicodemother.model.enums.GenerationModelUsageSource;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.service.trace.GenerationTracePersistenceService.NewModelCall;
import com.rush.rushaicodemother.service.trace.GenerationTracePersistenceService.NewTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultGenerationTracePersistenceServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 14, 8, 0);
    private static final String HASH = "a".repeat(64);
    private static final GenerationExecutionFence FENCE =
            new GenerationExecutionFence("task-1", "worker-a", 7L);

    private GenerationTraceMapper mapper;
    private DefaultGenerationTracePersistenceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(GenerationTraceMapper.class);
        service = new DefaultGenerationTracePersistenceService(mapper);
    }

    @Test
    void insertTaskMustUseRunningStateAndAutoIncrementEntity() {
        when(mapper.insertTask(any())).thenReturn(1);

        assertTrue(service.insertTask(newTask()));

        ArgumentCaptor<GenerationTask> captor = ArgumentCaptor.forClass(GenerationTask.class);
        verify(mapper).insertTask(captor.capture());
        assertEquals(GenerationTaskStatus.RUNNING.getValue(), captor.getValue().getStatus());
        assertEquals("start", captor.getValue().getStage());
        assertEquals(NOW, captor.getValue().getStartTime());
    }

    @Test
    void enrichRuntimeTaskTraceMustDelegateAllNormalizedPayloadFields() {
        when(mapper.enrichRunningTaskTrace(
                10L, "html", "vue_project", "创建页面", "增强提示词",
                1, "strict", "agent", null, 0L, NOW
        )).thenReturn(1);

        assertTrue(service.enrichRuntimeTaskTrace(10L, newTask(), null, NOW));

        verify(mapper).enrichRunningTaskTrace(
                10L, "html", "vue_project", "创建页面", "增强提示词",
                1, "strict", "agent", null, 0L, NOW
        );
    }

    @Test
    void transitionRunningTaskTraceMustForwardRoutePayloadAndExecutionFence() {
        when(mapper.transitionRunningTaskTrace(
                10L, "html", "vue_project", "增强提示词",
                1, "strict", "agent", "worker-a", 7L, NOW
        )).thenReturn(1);

        service.transitionRunningTaskTrace(10L, newTask(), FENCE, NOW);

        verify(mapper).transitionRunningTaskTrace(
                10L, "html", "vue_project", "增强提示词",
                1, "strict", "agent", "worker-a", 7L, NOW
        );
    }

    @Test
    void duplicateTaskAndModelCallMustReturnFalseForBusinessIdempotencyRecovery() {
        when(mapper.insertTask(any())).thenThrow(new DuplicateKeyException("uk_taskId"));
        when(mapper.insertModelCall(any())).thenThrow(new DuplicateKeyException("uk_callId"));

        assertFalse(service.insertTask(newTask()));
        assertFalse(service.insertModelCall(newModelCall()));
    }

    @Test
    void writeOperationsMustRequireExactlyOneAffectedRow() {
        when(mapper.updateRunningTaskStage(
                10L, "build", "正在构建", null, 0L, NOW)).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> service.updateRunningTaskStage(
                        10L, "build", "正在构建", null, NOW));
    }

    @Test
    void fencedWriteMustForwardLeaseOwnerAndExecutionEpoch() {
        when(mapper.updateTaskMemorySummary(
                10L, "summary", "worker-a", 7L, NOW)).thenReturn(1);

        service.updateTaskMemorySummary(10L, "summary", FENCE, NOW);

        verify(mapper).updateTaskMemorySummary(
                10L, "summary", "worker-a", 7L, NOW);
    }

    @Test
    void modelCallMustPersistEnumAsStableDatabaseValue() {
        when(mapper.insertModelCall(any())).thenReturn(1);

        assertTrue(service.insertModelCall(newModelCall()));

        ArgumentCaptor<GenerationModelCall> captor = ArgumentCaptor.forClass(GenerationModelCall.class);
        verify(mapper).insertModelCall(captor.capture());
        assertEquals(GenerationModelUsageSource.OFFICIAL.name(), captor.getValue().getUsageSource());
        assertEquals(GenerationModelCallStatus.SUCCESS.name(), captor.getValue().getCallStatus());
        assertEquals(HASH, captor.getValue().getPromptTemplateHash());
        assertEquals("c47e4463-57c6-4e72-8424-fd5c1873a64e", captor.getValue().getCallId());
    }

    @Test
    void corruptedTaskRowsMustFailInsteadOfLeakingPartialEntities() {
        when(mapper.selectTaskByTaskId("task-1")).thenReturn(GenerationTask.builder()
                .taskId("task-1")
                .appId(1L)
                .userId(2L)
                .status("running")
                .startTime(NOW)
                .createTime(NOW)
                .build());

        assertThrows(BusinessException.class, () -> service.findTaskByTaskId("task-1"));
    }

    private NewTask newTask() {
        return new NewTask(
                "task-1", 1L, 2L, "html", "vue_project",
                "创建页面", "增强提示词", true, "strict", "agent", NOW
        );
    }

    private NewModelCall newModelCall() {
        return new NewModelCall(
                "c47e4463-57c6-4e72-8424-fd5c1873a64e", "task-1", 1L, 2L,
                "openai", "gpt-test", GenerationModelCallStatus.SUCCESS, "response-1",
                8, 5, 13, 125L,
                "STOP", GenerationModelUsageSource.OFFICIAL, null,
                HASH, HASH, HASH, HASH, 2, 3, "{}", NOW
        );
    }
}
