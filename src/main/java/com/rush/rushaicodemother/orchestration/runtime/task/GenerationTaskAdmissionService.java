package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskAdmissionRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskIdempotencyRecord;
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
import java.util.Optional;

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
                command.tenantId(), command.userId(), command.appId());
        Optional<GenerationTaskSubmissionReceipt> replay = findMatchingReplay(
                command.tenantId(), command.userId(), command.appId(), idempotency);
        if (replay.isPresent()) {
            return GenerationTaskAdmissionResult.reused(replay.get());
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
        return GenerationTaskAdmissionResult.created(
                GenerationTaskSubmissionReceipt.queued(command, quote));
    }

    /**
     * 在任何 preflight provider 调用前识别已存在的幂等任务。
     *
     * <p>这是快速路径；最终准入仍会在同一锁域下重做检查，以覆盖并发首次提交。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<GenerationTaskSubmissionReceipt> findIdempotentReplay(
            GenerationTaskRequest request,
            GenerationTaskIdempotency idempotency) {
        Objects.requireNonNull(idempotency, "生成任务幂等信息不能为空");
        if (!idempotency.present()) {
            return Optional.empty();
        }
        if (request == null || request.app() == null || request.loginUser() == null
                || request.app().getId() == null || request.app().getId() <= 0
                || request.app().getTenantId() == null || request.app().getTenantId() <= 0
                || request.loginUser().getId() == null || request.loginUser().getId() <= 0) {
            throw new IllegalArgumentException("幂等预检身份不完整");
        }
        admissionRepository.lockScopeAndMeasure(
                request.app().getTenantId(), request.loginUser().getId(), request.app().getId());
        return findMatchingReplay(
                request.app().getTenantId(),
                request.loginUser().getId(),
                request.app().getId(),
                idempotency);
    }

    /**
     * 在可选模型澄清前执行保守门禁，并以独立短事务冻结最坏路线额度。
     *
     * <p>模型调用结束后不持有数据库锁；最终准入重做策略判断并接管这笔预授权。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void assertMayPreflight(String taskId,
                                   GenerationTaskRequest request,
                                   CodeGenTypeEnum targetType,
                                   IntentProfile intentProfile) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("preflight 任务 ID 不合法");
        }
        if (request == null || request.app() == null || request.loginUser() == null
                || request.app().getTenantId() == null || request.app().getTenantId() <= 0
                || request.loginUser().getId() == null || request.loginUser().getId() <= 0) {
            throw new IllegalArgumentException("preflight 准入身份不完整");
        }
        Objects.requireNonNull(targetType, "preflight 目标类型不能为空");
        Objects.requireNonNull(intentProfile, "preflight 意图画像不能为空");
        aiModelRuntimeService.ensureGenerationModelsConfigured();
        GenerationCreditReservationQuote upperBoundQuote = reservationPolicy.quoteUpperBound(targetType);
        GenerationTaskAdmissionSnapshot snapshot = admissionRepository.lockScopeAndMeasure(
                request.app().getTenantId(), request.loginUser().getId(), request.app().getId());
        GenerationTaskPreflightAdmissionContext context = new GenerationTaskPreflightAdmissionContext(
                request.app().getTenantId(),
                request.loginUser().getId(),
                targetType,
                intentProfile,
                snapshot,
                upperBoundQuote);
        admissionPolicies.stream()
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .forEach(policy -> policy.assertMayPreflight(context));
        userCreditService.reserveGenerationPreflight(new GenerationCreditReservationCommand(
                taskId,
                request.loginUser().getId(),
                request.app().getTenantId(),
                upperBoundQuote.reservedCredit(),
                upperBoundQuote.pricingReference()
        ));
    }

    /** 回收没有被正式任务接管的预检预授权。 */
    public void settlePreflightReservation(String taskId) {
        userCreditService.settleGenerationPreflight(taskId);
    }

    private Optional<GenerationTaskSubmissionReceipt> findMatchingReplay(
            Long tenantId,
            Long userId,
            Long appId,
            GenerationTaskIdempotency idempotency) {
        if (!idempotency.present()) {
            return Optional.empty();
        }
        GenerationTaskIdempotencyRecord existing = admissionRepository.findByIdempotencyKey(
                tenantId, userId, appId, idempotency.keyHash()).orElse(null);
        if (existing == null) {
            return Optional.empty();
        }
        if (!Objects.equals(existing.requestFingerprint(), idempotency.requestFingerprint())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT_ERROR,
                    "Idempotency-Key 已被其他生成请求使用");
        }
        return Optional.of(existing.submission());
    }
}
