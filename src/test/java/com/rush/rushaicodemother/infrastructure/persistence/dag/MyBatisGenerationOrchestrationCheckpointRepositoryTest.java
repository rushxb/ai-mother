package com.rush.rushaicodemother.infrastructure.persistence.dag;

import com.rush.rushaicodemother.mapper.GenerationOrchestrationCheckpointMapper;
import com.rush.rushaicodemother.model.entity.GenerationOrchestrationCheckpoint;
import com.rush.rushaicodemother.orchestration.dag.AgentRuntimeState;
import com.rush.rushaicodemother.orchestration.dag.GenerationCheckpointPersistenceException;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisGenerationOrchestrationCheckpointRepositoryTest {

    private GenerationOrchestrationCheckpointMapper mapper;
    private MyBatisGenerationOrchestrationCheckpointRepository repository;

    @BeforeEach
    void setUp() {
        mapper = mock(GenerationOrchestrationCheckpointMapper.class);
        repository = new MyBatisGenerationOrchestrationCheckpointRepository(mapper);
    }

    @Test
    void saveMustInsertWhenNoCheckpointExists() {
        GenerationOrchestrationTask task = task(2L);
        when(mapper.updateCheckpointIfNotStale(org.mockito.Mockito.any())).thenReturn(0);
        when(mapper.insertCheckpoint(org.mockito.Mockito.any())).thenReturn(1);

        repository.save(task, "{\"taskId\":\"task-1\"}", 19);

        ArgumentCaptor<GenerationOrchestrationCheckpoint> captor =
                ArgumentCaptor.forClass(GenerationOrchestrationCheckpoint.class);
        verify(mapper).insertCheckpoint(captor.capture());
        GenerationOrchestrationCheckpoint checkpoint = captor.getValue();
        assertEquals("task-1", checkpoint.getTaskId());
        assertEquals(1L, checkpoint.getAppId());
        assertEquals(2L, checkpoint.getCheckpointVersion());
        assertEquals(3L, checkpoint.getExecutionEpoch());
        assertEquals("RUNNING", checkpoint.getRuntimeState());
        assertEquals(19, checkpoint.getPayloadBytes());
    }

    @Test
    void saveMustPreferNonStaleUpdateForExistingCheckpoint() {
        GenerationOrchestrationTask task = task(3L);
        when(mapper.updateCheckpointIfNotStale(org.mockito.Mockito.any())).thenReturn(1);

        repository.save(task, "{}", 2);

        verify(mapper).updateCheckpointIfNotStale(org.mockito.Mockito.any());
    }

    @Test
    void duplicateInsertMustRetryNonStaleUpdate() {
        GenerationOrchestrationTask task = task(4L);
        when(mapper.updateCheckpointIfNotStale(org.mockito.Mockito.any())).thenReturn(0, 1);
        when(mapper.insertCheckpoint(org.mockito.Mockito.any()))
                .thenThrow(new DuplicateKeyException("uk_generation_orchestration_task"));

        repository.save(task, "{}", 2);

        verify(mapper, org.mockito.Mockito.times(2))
                .updateCheckpointIfNotStale(org.mockito.Mockito.any());
    }

    @Test
    void zeroRowInsertMustExposeFenceRejection() {
        GenerationOrchestrationTask task = task(4L);
        when(mapper.updateCheckpointIfNotStale(org.mockito.Mockito.any())).thenReturn(0);
        when(mapper.insertCheckpoint(org.mockito.Mockito.any())).thenReturn(0);

        GenerationCheckpointPersistenceException failure = assertThrows(
                GenerationCheckpointPersistenceException.class,
                () -> repository.save(task, "{}", 2));

        assertEquals(GenerationCheckpointPersistenceException.Reason.STALE_EXECUTION_FENCE,
                failure.reason());
    }

    @Test
    void duplicateInsertWithRejectedRetryMustExposeStaleCheckpoint() {
        GenerationOrchestrationTask task = task(5L);
        when(mapper.updateCheckpointIfNotStale(org.mockito.Mockito.any())).thenReturn(0);
        when(mapper.insertCheckpoint(org.mockito.Mockito.any()))
                .thenThrow(new DuplicateKeyException("uk_generation_orchestration_task"));

        GenerationCheckpointPersistenceException failure = assertThrows(
                GenerationCheckpointPersistenceException.class,
                () -> repository.save(task, "{}", 2));

        assertEquals(GenerationCheckpointPersistenceException.Reason.STALE_EXECUTION_FENCE,
                failure.reason());
    }

    @Test
    void loadAndDeleteMustDelegateToMapper() {
        when(mapper.selectPayload(1L, "task-1")).thenReturn("{}");

        Optional<String> payload = repository.loadPayload(1L, "task-1");
        repository.delete(1L, "task-1", 3L);

        assertTrue(payload.isPresent());
        assertEquals("{}", payload.get());
        verify(mapper).softDelete(1L, "task-1", 3L);
    }

    private GenerationOrchestrationTask task(long checkpointVersion) {
        GenerationOrchestrationTask task = new GenerationOrchestrationTask();
        task.setTaskId("task-1");
        task.setAppId(1L);
        task.setRequestHash("a".repeat(64));
        task.setStatus("running");
        task.setRuntimeState(AgentRuntimeState.RUNNING);
        task.setCurrentNode("planner");
        task.setExecutionEpoch(3L);
        task.setCheckpointVersion(checkpointVersion);
        return task;
    }
}
