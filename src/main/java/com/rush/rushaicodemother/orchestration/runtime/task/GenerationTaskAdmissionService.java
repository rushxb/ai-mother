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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** 以原子方式保留任务预算并保留其可重建的持久命令。 */
@Service
public class GenerationTaskAdmissionService {

    private final GenerationCreditReservationPolicy reservationPolicy;
    private final List<GenerationTaskAdmissionPolicy> admissionPolicies;
    private final GenerationTaskAdmissionRepository admissionRepository;
    private final AiModelRuntimeService aiModelRuntimeService;
    private final UserCreditService userCreditService;
    private final GenerationTaskRuntimeLifecycleService runtimeLifecycleService;

    @Autowired
    public GenerationTaskAdmissionService(GenerationCreditReservationPolicy reservationPolicy,
                                          List<GenerationTaskAdmissionPolicy> admissionPolicies,
                                          GenerationTaskAdmissionRepository admissionRepository,
                                          AiModelRuntimeService aiModelRuntimeService,
                                          UserCreditService userCreditService,
                                          GenerationTaskRuntimeLifecycleService runtimeLifecycleService) {
        this.reservationPolicy = reservationPolicy;
        this.admissionPolicies = List.copyOf(admissionPolicies);
        this.admissionRepository = admissionRepository;
        this.aiModelRuntimeService = aiModelRuntimeService;
        this.userCreditService = userCreditService;
        this.runtimeLifecycleService = runtimeLifecycleService;
    }

    /** 兼容不需要扩展策略集合的既有构造入口。 */
    GenerationTaskAdmissionService(GenerationCreditReservationPolicy reservationPolicy,
                                   GenerationTaskConcurrencyAdmissionPolicy concurrencyAdmissionPolicy,
                                   GenerationTaskAdmissionRepository admissionRepository,
                                   AiModelRuntimeService aiModelRuntimeService,
                                   UserCreditService userCreditService,
                                   GenerationTaskRuntimeLifecycleService runtimeLifecycleService) {
        this(reservationPolicy, List.of(concurrencyAdmissionPolicy), admissionRepository,
                aiModelRuntimeService, userCreditService, runtimeLifecycleService);
    }

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
        Objects.requireNonNull(command, "生成任务命令不能为空");
        Objects.requireNonNull(idempotency, "生成任务幂等信息不能为空");
        GenerationTaskAdmissionSnapshot snapshot = admissionRepository.lockScopeAndMeasure(
                command.tenantId(), command.userId());
        if (idempotency.present()) {
            GenerationTaskIdempotencyRecord existing = admissionRepository.findByIdempotencyKey(
                    command.tenantId(), command.userId(), command.appId(), idempotency.keyHash()
            ).orElse(null);
            if (existing != null) {
                if (!Objects.equals(existing.requestFingerprint(), idempotency.requestFingerprint())) {
                    throw new BusinessException(
                            ErrorCode.CONFLICT_ERROR,
                            "Idempotency-Key 已被其他生成请求使用"
                    );
                }
                return GenerationTaskAdmissionResult.reused(existing.submission());
            }
        }

        aiModelRuntimeService.ensureGenerationModelsConfigured();
        GenerationCreditReservationQuote quote = reservationPolicy.quote(command);
        GenerationTaskAdmissionContext context = new GenerationTaskAdmissionContext(command, snapshot, quote);
        admissionPolicies.stream()
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .forEach(policy -> policy.assertMayAdmit(context));
        userCreditService.reserveGenerationTask(new GenerationCreditReservationCommand(
                command.taskId(),
                command.userId(),
                command.tenantId(),
                quote.reservedCredit(),
                quote.pricingReference()
        ));
        runtimeLifecycleService.submit(command, idempotency);
        return GenerationTaskAdmissionResult.created(GenerationTaskSubmissionReceipt.queued(command));
    }
}
