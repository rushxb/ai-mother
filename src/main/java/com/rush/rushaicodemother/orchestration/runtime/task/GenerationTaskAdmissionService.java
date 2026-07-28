package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskAdmissionRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskIdempotencyRecord;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeService;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationCommand;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationPolicy;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationQuote;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 以原子方式保留任务预算并保留其可重建的持久命令。 */
@Service
@RequiredArgsConstructor
public class GenerationTaskAdmissionService {

    private final GenerationCreditReservationPolicy reservationPolicy;
    private final GenerationTaskConcurrencyAdmissionPolicy concurrencyAdmissionPolicy;
    private final GenerationTaskAdmissionRepository admissionRepository;
    private final AiModelRuntimeService aiModelRuntimeService;
    private final UserCreditService userCreditService;
    private final GenerationTaskRuntimeLifecycleService runtimeLifecycleService;

    /**
 * 返回{@code admit}。
 *
 * @param command 命令
 * @return 生成任务准入
 */
    @Transactional(rollbackFor = Exception.class)
    public GenerationTaskAdmissionResult admit(GenerationTaskCommand command) {
        return admit(command, GenerationTaskIdempotency.none());
    }

    /**
 * 返回{@code admit}。
 *
 * @param command 命令
 * @param idempotency {@code idempotency} 对应的调用参数
 * @return 生成任务准入
 */
    @Transactional(rollbackFor = Exception.class)
    public GenerationTaskAdmissionResult admit(GenerationTaskCommand command,
                                               GenerationTaskIdempotency idempotency) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(idempotency, "idempotency");
        int currentNonTerminalTasks = admissionRepository.lockUserAndCountNonTerminalTasks(command.userId());
        if (idempotency.present()) {
            GenerationTaskIdempotencyRecord existing = admissionRepository.findByIdempotencyKey(
                    command.tenantId(), command.userId(), command.appId(), idempotency.keyHash()
            ).orElse(null);
            if (existing != null) {
                if (!Objects.equals(existing.requestFingerprint(), idempotency.requestFingerprint())) {
                    throw new BusinessException(
                            ErrorCode.CONFLICT_ERROR,
                            "Idempotency-Key has already been used for a different generation request"
                    );
                }
                return GenerationTaskAdmissionResult.reused(existing.submission());
            }
        }

        aiModelRuntimeService.ensureGenerationModelsConfigured();
        concurrencyAdmissionPolicy.assertMayCreate(currentNonTerminalTasks);
        GenerationCreditReservationQuote quote = reservationPolicy.quote(command);
        userCreditService.reserveGenerationTask(new GenerationCreditReservationCommand(
                command.taskId(),
                command.userId(),
                quote.reservedCredit(),
                quote.pricingReference()
        ));
        runtimeLifecycleService.submit(command, idempotency);
        return GenerationTaskAdmissionResult.created(GenerationTaskSubmissionReceipt.queued(command));
    }
}
