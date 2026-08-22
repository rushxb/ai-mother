package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MyBatisGenerationTaskAdmissionRepositoryTest {

    @Test
    void repositoryMustLockTenantUserAndApplicationBeforeReadingAdmissionFacts() {
        GenerationTaskRuntimeMapper mapper = mock(GenerationTaskRuntimeMapper.class);
        when(mapper.lockActiveTenantForGenerationAdmission(100L)).thenReturn(100L);
        when(mapper.lockActiveUserForGenerationAdmission(7L)).thenReturn(7L);
        when(mapper.lockActiveApplicationForSubmission(11L))
                .thenReturn(App.builder().id(11L).tenantId(100L).build());
        when(mapper.countNonTerminalTasksByUserId(7L)).thenReturn(3);
        when(mapper.countNonTerminalTasksByAppId(11L)).thenReturn(1);
        when(mapper.countNonTerminalTasksByTenantId(100L)).thenReturn(8);
        when(mapper.countNonTerminalHeavyTasksByTenantId(100L)).thenReturn(2);
        when(mapper.sumTenantGenerationCreditUsage(org.mockito.ArgumentMatchers.eq(100L), any(), any()))
                .thenReturn(900L);
        MyBatisGenerationTaskAdmissionRepository repository =
                new MyBatisGenerationTaskAdmissionRepository(mapper);

        var snapshot = repository.lockScopeAndMeasure(100L, 7L, 11L);

        assertEquals(3, snapshot.userNonTerminalTasks());
        assertEquals(1, snapshot.appNonTerminalTasks());
        assertEquals(8, snapshot.tenantNonTerminalTasks());
        assertEquals(2, snapshot.tenantHeavyNonTerminalTasks());
        assertEquals(900L, snapshot.tenantMonthlyCreditUsage());

        var order = inOrder(mapper);
        order.verify(mapper).lockActiveTenantForGenerationAdmission(100L);
        order.verify(mapper).lockActiveUserForGenerationAdmission(7L);
        order.verify(mapper).lockActiveApplicationForSubmission(11L);
        order.verify(mapper).countNonTerminalTasksByUserId(7L);
        order.verify(mapper).countNonTerminalTasksByAppId(11L);
        order.verify(mapper).countNonTerminalTasksByTenantId(100L);
        order.verify(mapper).countNonTerminalHeavyTasksByTenantId(100L);
        order.verify(mapper).sumTenantGenerationCreditUsage(
                org.mockito.ArgumentMatchers.eq(100L), any(), any());
    }

    @Test
    void missingOrInvalidUsersMustFailBeforeAnAdmissionDecision() {
        GenerationTaskRuntimeMapper mapper = mock(GenerationTaskRuntimeMapper.class);
        MyBatisGenerationTaskAdmissionRepository repository =
                new MyBatisGenerationTaskAdmissionRepository(mapper);

        assertThrows(IllegalArgumentException.class,
                () -> repository.lockScopeAndMeasure(100L, 0L, 11L));
        verifyNoInteractions(mapper);

        assertThrows(BusinessException.class,
                () -> repository.lockScopeAndMeasure(100L, 7L, 11L));
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
