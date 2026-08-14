package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipeline;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineCapability;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineCapabilityRegistry;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineOutcome;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskAdmissionRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskIdempotencyRecord;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeService;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationPolicy;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationQuote;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationTaskCapabilityAdmissionTest {

    private static final GenerationTaskIdempotency IDEMPOTENCY =
            new GenerationTaskIdempotency("a".repeat(64), "b".repeat(64));

    @Test
    void unsupportedScenarioMustFailBeforeCreditReservationAndPersistence() {
        Fixture fixture = fixture();
        GenerationTaskCommand command = command();
        when(fixture.repository().lockScopeAndMeasure(100L, 7L)).thenReturn(snapshot());
        when(fixture.reservationPolicy().quote(command)).thenReturn(
                new GenerationCreditReservationQuote(100_000L, 2L, "test-price"));

        BusinessException rejection = assertThrows(
                BusinessException.class,
                () -> fixture.service().admit(command));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), rejection.getCode());
        verify(fixture.creditService(), never()).reserveGenerationTask(any());
        verifyNoInteractions(fixture.lifecycleService());
    }

    @Test
    void idempotentReplayMustWinBeforeCapabilityRevalidation() {
        Fixture fixture = fixture();
        when(fixture.repository().lockScopeAndMeasure(100L, 7L)).thenReturn(snapshot());
        when(fixture.repository().findByIdempotencyKey(
                100L, 7L, 1L, IDEMPOTENCY.keyHash()))
                .thenReturn(Optional.of(new GenerationTaskIdempotencyRecord(
                        receipt(), IDEMPOTENCY.requestFingerprint())));

        GenerationTaskAdmissionResult result = fixture.service().admit(command(), IDEMPOTENCY);

        assertEquals(GenerationTaskAdmissionResult.Disposition.REUSED, result.disposition());
        verifyNoInteractions(
                fixture.aiModelRuntimeService(),
                fixture.reservationPolicy(),
                fixture.creditService(),
                fixture.lifecycleService());
    }

    private Fixture fixture() {
        GenerationCreditReservationPolicy reservationPolicy =
                mock(GenerationCreditReservationPolicy.class);
        GenerationTaskAdmissionRepository repository =
                mock(GenerationTaskAdmissionRepository.class);
        AiModelRuntimeService aiModelRuntimeService = mock(AiModelRuntimeService.class);
        UserCreditService creditService = mock(UserCreditService.class);
        GenerationTaskRuntimeLifecycleService lifecycleService =
                mock(GenerationTaskRuntimeLifecycleService.class);
        GenerationPipelineCapabilityRegistry registry = new GenerationPipelineCapabilityRegistry(
                List.of(pipeline(GenerationPipelineCapability.write(
                        "create",
                        EnumSet.of(IntentOperationType.CREATE),
                        EnumSet.of(CodeGenTypeEnum.VUE_PROJECT),
                        EnumSet.of(GenerationMode.CREATE)))));
        GenerationTaskCapabilityAdmissionPolicy capabilityPolicy =
                new GenerationTaskCapabilityAdmissionPolicy(registry);
        GenerationTaskAdmissionService service = new GenerationTaskAdmissionService(
                reservationPolicy,
                List.of(capabilityPolicy),
                repository,
                aiModelRuntimeService,
                creditService,
                lifecycleService);
        return new Fixture(
                service,
                reservationPolicy,
                repository,
                aiModelRuntimeService,
                creditService,
                lifecycleService);
    }

    private GenerationPipeline pipeline(GenerationPipelineCapability capability) {
        return new GenerationPipeline() {
            @Override
            public String route() {
                return capability.route();
            }

            @Override
            public GenerationPipelineCapability capability() {
                return capability;
            }

            @Override
            public GenerationPipelineOutcome execute(GenerationPipelineRequest request) {
                throw new UnsupportedOperationException("测试管线不执行任务");
            }
        };
    }

    private GenerationTaskCommand command() {
        Instant now = Instant.parse("2026-08-15T00:00:00Z");
        return new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                "task-capability",
                1L,
                7L,
                100L,
                "修改标题",
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationMode.LIGHT_EDIT,
                0.9,
                "测试路由",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD,
                "",
                now,
                now.plusSeconds(600));
    }

    private GenerationTaskSubmissionReceipt receipt() {
        Instant submittedAt = Instant.parse("2026-08-14T23:55:00Z");
        return new GenerationTaskSubmissionReceipt(
                "task-original",
                1L,
                "lightweight_edit",
                GenerationTaskStatus.RUNNING,
                submittedAt,
                submittedAt.plusSeconds(600));
    }

    private GenerationTaskAdmissionSnapshot snapshot() {
        return new GenerationTaskAdmissionSnapshot(0, 0, 0, 0L);
    }

    private record Fixture(
            GenerationTaskAdmissionService service,
            GenerationCreditReservationPolicy reservationPolicy,
            GenerationTaskAdmissionRepository repository,
            AiModelRuntimeService aiModelRuntimeService,
            UserCreditService creditService,
            GenerationTaskRuntimeLifecycleService lifecycleService
    ) {
    }
}
