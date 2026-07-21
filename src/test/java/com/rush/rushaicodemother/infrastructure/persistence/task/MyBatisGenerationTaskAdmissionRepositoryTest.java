package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import org.junit.jupiter.api.Test;

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
                        .route("heavy_generation")
                        .requestFingerprint("b".repeat(64))
                        .build());
        MyBatisGenerationTaskAdmissionRepository repository =
                new MyBatisGenerationTaskAdmissionRepository(mapper);

        var existing = repository.findByIdempotencyKey(100L, 7L, 11L, keyHash).orElseThrow();

        assertEquals("task-existing", existing.taskId());
        assertEquals("heavy_generation", existing.route());
        assertEquals("b".repeat(64), existing.requestFingerprint());
    }
}
