package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MyBatisGenerationTaskAdmissionRepositoryTest {

    @Test
    void repositoryMustLockTheUserBeforeReadingCurrentOutstandingLoad() {
        GenerationTaskRuntimeMapper mapper = mock(GenerationTaskRuntimeMapper.class);
        when(mapper.lockActiveUserForGenerationAdmission(7L)).thenReturn(7L);
        when(mapper.countNonTerminalTasksByUserId(7L)).thenReturn(3);
        MyBatisGenerationTaskAdmissionRepository repository =
                new MyBatisGenerationTaskAdmissionRepository(mapper);

        assertEquals(3, repository.lockUserAndCountNonTerminalTasks(7L));

        var order = inOrder(mapper);
        order.verify(mapper).lockActiveUserForGenerationAdmission(7L);
        order.verify(mapper).countNonTerminalTasksByUserId(7L);
    }

    @Test
    void missingOrInvalidUsersMustFailBeforeAnAdmissionDecision() {
        GenerationTaskRuntimeMapper mapper = mock(GenerationTaskRuntimeMapper.class);
        MyBatisGenerationTaskAdmissionRepository repository =
                new MyBatisGenerationTaskAdmissionRepository(mapper);

        assertThrows(IllegalArgumentException.class,
                () -> repository.lockUserAndCountNonTerminalTasks(0L));
        verifyNoInteractions(mapper);

        assertThrows(BusinessException.class,
                () -> repository.lockUserAndCountNonTerminalTasks(7L));
    }

    @Test
    void idempotencyLookupMustRemainScopedToTenantUserAndApplication() {
        GenerationTaskRuntimeMapper mapper = mock(GenerationTaskRuntimeMapper.class);
        String keyHash = "a".repeat(64);
        when(mapper.selectBySubmissionIdempotency(100L, 7L, 11L, keyHash))
                .thenReturn(GenerationTask.builder()
                        .taskId("task-existing")
                        .appId(11L)
                        .route("heavy_generation")
                        .status(GenerationTaskStatus.RUNNING.getValue())
                        .submittedAt(LocalDateTime.of(2026, 7, 20, 10, 0))
                        .deadlineAt(LocalDateTime.of(2026, 7, 20, 10, 15))
                        .requestFingerprint("b".repeat(64))
                        .build());
        MyBatisGenerationTaskAdmissionRepository repository =
                new MyBatisGenerationTaskAdmissionRepository(mapper);

        var existing = repository.findByIdempotencyKey(100L, 7L, 11L, keyHash).orElseThrow();

        assertEquals("task-existing", existing.submission().taskId());
        assertEquals(11L, existing.submission().appId());
        assertEquals("heavy_generation", existing.submission().route());
        assertEquals(GenerationTaskStatus.RUNNING, existing.submission().status());
        assertEquals("b".repeat(64), existing.requestFingerprint());
    }
}
