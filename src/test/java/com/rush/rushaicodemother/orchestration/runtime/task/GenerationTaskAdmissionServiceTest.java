package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskAdmissionRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskIdempotencyRecord;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeService;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationCommand;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationPolicy;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationQuote;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationTaskAdmissionServiceTest {

    private static final GenerationTaskIdempotency IDEMPOTENCY =
            new GenerationTaskIdempotency("a".repeat(64), "b".repeat(64));

    @Test
    void newSubmissionMustCheckIdempotencyBeforeQuotaCreditAndDurablePersistence() {
        Fixture fixture = fixture();
        GenerationTaskCommand command = command();
        when(fixture.repository().lockUserAndCountNonTerminalTasks(7L)).thenReturn(2);
        when(fixture.repository().findByIdempotencyKey(100L, 7L, 1L, IDEMPOTENCY.keyHash()))
                .thenReturn(Optional.empty());
        when(fixture.reservationPolicy().quote(command)).thenReturn(new GenerationCreditReservationQuote(
                200_000L, 2L, "policy-v1:LIGHT_EDIT:VUE_PROJECT"));

        GenerationTaskAdmissionResult result = fixture.service().admit(command, IDEMPOTENCY);

        assertTrue(result.created());
        assertEquals("task-1", result.taskId());
        ArgumentCaptor<GenerationCreditReservationCommand> reservationCaptor =
                ArgumentCaptor.forClass(GenerationCreditReservationCommand.class);
        InOrder order = inOrder(fixture.repository(), fixture.aiModelRuntimeService(), fixture.concurrencyPolicy(),
                fixture.reservationPolicy(), fixture.creditService(), fixture.lifecycleService());
        order.verify(fixture.repository()).lockUserAndCountNonTerminalTasks(7L);
        order.verify(fixture.repository()).findByIdempotencyKey(100L, 7L, 1L, IDEMPOTENCY.keyHash());
        order.verify(fixture.aiModelRuntimeService()).ensureGenerationModelsConfigured();
        order.verify(fixture.concurrencyPolicy()).assertMayCreate(2);
        order.verify(fixture.reservationPolicy()).quote(command);
        order.verify(fixture.creditService()).reserveGenerationTask(reservationCaptor.capture());
        order.verify(fixture.lifecycleService()).submit(command, IDEMPOTENCY);
        assertEquals("task-1", reservationCaptor.getValue().taskId());
        assertEquals(7L, reservationCaptor.getValue().userId());
        assertEquals(2L, reservationCaptor.getValue().reservedCredit());
    }

    @Test
    void matchingRetryMustReuseOriginalTaskBeforeQuotaAndCreditChecks() {
        Fixture fixture = fixture();
        when(fixture.repository().lockUserAndCountNonTerminalTasks(7L)).thenReturn(4);
        when(fixture.repository().findByIdempotencyKey(100L, 7L, 1L, IDEMPOTENCY.keyHash()))
                .thenReturn(Optional.of(new GenerationTaskIdempotencyRecord(
                        "task-original", "heavy_generation", IDEMPOTENCY.requestFingerprint())));

        GenerationTaskAdmissionResult result = fixture.service().admit(command(), IDEMPOTENCY);

        assertEquals(GenerationTaskAdmissionResult.Disposition.REUSED, result.disposition());
        assertEquals("task-original", result.taskId());
        assertEquals("heavy_generation", result.route());
        verifyNoInteractions(fixture.aiModelRuntimeService(), fixture.concurrencyPolicy(), fixture.reservationPolicy(),
                fixture.creditService(), fixture.lifecycleService());
    }

    @Test
    void reusedKeyWithDifferentRequestMustFailWithoutSideEffects() {
        Fixture fixture = fixture();
        when(fixture.repository().lockUserAndCountNonTerminalTasks(7L)).thenReturn(0);
        when(fixture.repository().findByIdempotencyKey(100L, 7L, 1L, IDEMPOTENCY.keyHash()))
                .thenReturn(Optional.of(new GenerationTaskIdempotencyRecord(
                        "task-original", "lightweight_edit", "c".repeat(64))));

        BusinessException conflict = assertThrows(BusinessException.class,
                () -> fixture.service().admit(command(), IDEMPOTENCY));

        assertEquals(ErrorCode.CONFLICT_ERROR.getCode(), conflict.getCode());
        verifyNoInteractions(fixture.aiModelRuntimeService(), fixture.concurrencyPolicy(), fixture.reservationPolicy(),
                fixture.creditService(), fixture.lifecycleService());
    }

    @Test
    void reservationFailureMustPreventDurableSubmission() {
        Fixture fixture = fixture();
        GenerationTaskCommand command = command();
        GenerationCreditReservationQuote quote = new GenerationCreditReservationQuote(
                200_000L, 2L, "policy-v1");
        when(fixture.repository().lockUserAndCountNonTerminalTasks(7L)).thenReturn(0);
        when(fixture.reservationPolicy().quote(command)).thenReturn(quote);
        IllegalStateException failure = new IllegalStateException("insufficient credit");
        doThrow(failure).when(fixture.creditService()).reserveGenerationTask(
                new GenerationCreditReservationCommand("task-1", 7L, 2L, "policy-v1"));

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> fixture.service().admit(command)));

        verify(fixture.lifecycleService(), never()).submit(command, GenerationTaskIdempotency.none());
    }

    private Fixture fixture() {
        GenerationCreditReservationPolicy reservationPolicy = mock(GenerationCreditReservationPolicy.class);
        GenerationTaskConcurrencyAdmissionPolicy concurrencyPolicy =
                mock(GenerationTaskConcurrencyAdmissionPolicy.class);
        GenerationTaskAdmissionRepository repository = mock(GenerationTaskAdmissionRepository.class);
        AiModelRuntimeService aiModelRuntimeService = mock(AiModelRuntimeService.class);
        UserCreditService creditService = mock(UserCreditService.class);
        GenerationTaskRuntimeLifecycleService lifecycleService =
                mock(GenerationTaskRuntimeLifecycleService.class);
        return new Fixture(
                new GenerationTaskAdmissionService(
                        reservationPolicy, concurrencyPolicy, repository, aiModelRuntimeService,
                        creditService, lifecycleService),
                reservationPolicy, concurrencyPolicy, repository, aiModelRuntimeService,
                creditService, lifecycleService);
    }

    private GenerationTaskCommand command() {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        return new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                "task-1",
                1L,
                7L,
                100L,
                "update title",
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationMode.LIGHT_EDIT,
                0.9,
                "test",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD,
                "",
                now,
                now.plusSeconds(600)
        );
    }

    private record Fixture(
            GenerationTaskAdmissionService service,
            GenerationCreditReservationPolicy reservationPolicy,
            GenerationTaskConcurrencyAdmissionPolicy concurrencyPolicy,
            GenerationTaskAdmissionRepository repository,
            AiModelRuntimeService aiModelRuntimeService,
            UserCreditService creditService,
            GenerationTaskRuntimeLifecycleService lifecycleService
    ) {
    }
}
