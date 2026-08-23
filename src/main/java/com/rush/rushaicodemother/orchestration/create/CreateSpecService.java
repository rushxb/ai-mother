package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.AiCreateSpecService;
import com.rush.rushaicodemother.ai.AiCreateSpecServiceFactory;
import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationSynchronousModelCallSupervisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 生成并标准化紧凑的 CREATE 规范。
 */
@Slf4j
@Service
public class CreateSpecService {

    private final AiCreateSpecServiceFactory serviceFactory;
    private final CreateSpecDefaults defaults = new CreateSpecDefaults();
    private final CreateSpecNormalizer normalizer;
    private final GenerationExecutionContextService executionContextService;
    private final GenerationSynchronousModelCallSupervisor modelCallSupervisor;

    public CreateSpecService(AiCreateSpecServiceFactory serviceFactory) {
        this(serviceFactory, new CreateSpecNormalizer(), null, null);
    }

    public CreateSpecService(AiCreateSpecServiceFactory serviceFactory,
                             CreateSpecNormalizer normalizer) {
        this(serviceFactory, normalizer, null, null);
    }

    @Autowired
    public CreateSpecService(AiCreateSpecServiceFactory serviceFactory,
                             CreateSpecNormalizer normalizer,
                             GenerationExecutionContextService executionContextService,
                             GenerationSynchronousModelCallSupervisor modelCallSupervisor) {
        this.serviceFactory = Objects.requireNonNull(serviceFactory, "CREATE 规格模型工厂不能为空");
        this.normalizer = Objects.requireNonNull(normalizer, "CREATE 规格规范化器不能为空");
        if ((executionContextService == null) != (modelCallSupervisor == null)) {
            throw new IllegalArgumentException("CREATE 规格执行上下文与同步模型监督器必须同时配置");
        }
        this.executionContextService = executionContextService;
        this.modelCallSupervisor = modelCallSupervisor;
    }

    /**
 * 根据输入生成创建{@code Spec}。
 *
 * @param userMessage 用户消息
 * @param plan 计划
 * @return 创建{@code Spec}
 */
    public SpecResult generate(String userMessage, CreateGenerationPlan plan) {
        return generateInternal(userMessage, plan, aggregateGroupOrNull(plan), null);
    }

    /**
 * 根据输入生成{@code Managed}。
 *
 * @param taskId 任务编号
 * @param userMessage 用户消息
 * @param plan 计划
 * @return {@code Managed}
 */
    public SpecResult generateManaged(String taskId,
                                      String userMessage,
                                      CreateGenerationPlan plan) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("受管 CREATE 规格调用必须提供任务标识");
        }
        return generateInternal(userMessage, plan, aggregateGroupOrNull(plan), taskId);
    }

    private SlotGroup aggregateGroupOrNull(CreateGenerationPlan plan) {
        if (plan == null || plan.slotGroups().isEmpty()) {
            return null;
        }
        return aggregateGroup(plan);
    }

    public SpecResult generate(String userMessage, CreateGenerationPlan plan, SlotGroup group) {
        return generateInternal(userMessage, plan, group, null);
    }

    /** 根据输入生成内部。 */
    private SpecResult generateInternal(String userMessage,
                                        CreateGenerationPlan plan,
                                        SlotGroup group,
                                        String taskId) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (plan == null || group == null) {
            CreateSpecNormalizer.NormalizedSpec normalized = normalizer.normalize(
                    defaults.fromRequest(userMessage, plan, group, "create_spec_invalid_context"),
                    userMessage,
                    plan,
                    group
            );
            return SpecResult.available(
                    normalized.spec(),
                    "local_spec_invalid_context",
                    normalized.validation(),
                    false
            );
        }
        GenerationExecutionContext executionContext = resolveExecutionContext(taskId);
        Optional<Duration> modelTimeout = executionContext == null
                ? Optional.empty()
                : executionContext.optionalFirstPreviewOperationTimeout(
                executionContext.limits().modelCallTimeout());
        if (executionContext != null && modelTimeout.isEmpty()) {
            log.info("CREATE 规格模型已跳过，原因：首预览完成预留窗口已生效，taskId={}", taskId);
            return localSpec(
                    userMessage,
                    plan,
                    group,
                    "local_spec_first_preview_budget_exhausted",
                    false
            );
        }
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            AiCreateSpecService service = executionContext == null
                    ? serviceFactory.createService()
                    : createService(executionContext, modelTimeout.orElseThrow());
            CreateSpec spec = invokeModel(
                    service, executionContext, userMessage, plan, group);
            CreateSpecNormalizer.NormalizedSpec normalized = normalizer.normalize(spec, userMessage, plan, group);
            return SpecResult.available(normalized.spec(), "ai_spec", normalized.validation(), true);
        } catch (GenerationExecutionPolicyException policyFailure) {
            throw policyFailure;
        } catch (Exception e) {
            if (executionContext != null) {
                executionContext.assertCanContinue();
            }
            String reason = "create_spec_exception";
            log.warn("CREATE 规格生成失败，已回退到本地规格", LogExceptionSanitizer.sanitize(e));
            return localSpec(
                    userMessage,
                    plan,
                    group,
                    "local_spec_fallback:" + reason,
                    true
            );
        }
    }

    /** 受管模型调用统一响应任务取消和截止时间；非受管调用保持同步语义。 */
    private CreateSpec invokeModel(AiCreateSpecService service,
                                   GenerationExecutionContext executionContext,
                                   String userMessage,
                                   CreateGenerationPlan plan,
                                   SlotGroup group) {
        String safeUserMessage = StrUtil.blankToDefault(userMessage, "");
        String codeGenType = plan.codeGenType() == null ? "" : plan.codeGenType().getValue();
        String templateId = group.templateId();
        String modules = plannedModules(plan, group);
        if (executionContext == null) {
            return service.generateSpec(safeUserMessage, codeGenType, templateId, modules);
        }
        return modelCallSupervisor.execute(
                executionContext,
                () -> service.generateSpec(safeUserMessage, codeGenType, templateId, modules)
        );
    }

    SpecResult generateLocal(String userMessage, CreateGenerationPlan plan, String reason) {
        SlotGroup group = aggregateGroupOrNull(plan);
        return localSpec(
                userMessage,
                plan,
                group,
                StrUtil.blankToDefault(reason, "local_spec_explicit_fallback"),
                false
        );
    }

    /** 返回{@code local}{@code Spec}。 */
    private SpecResult localSpec(String userMessage,
                                 CreateGenerationPlan plan,
                                 SlotGroup group,
                                 String reason,
                                 boolean modelAttempted) {
        CreateSpecNormalizer.NormalizedSpec normalized = normalizer.normalize(
                defaults.fromRequest(userMessage, plan, group, reason),
                userMessage,
                plan,
                group
        );
        return SpecResult.available(
                normalized.spec(),
                reason,
                normalized.validation(),
                modelAttempted
        );
    }

    /** 根据当前上下文解析执行上下文。 */
    private GenerationExecutionContext resolveExecutionContext(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        if (executionContextService == null) {
            throw new GenerationExecutionPolicyException("CREATE 规格调用缺少任务执行上下文服务");
        }
        return executionContextService.getByTaskId(taskId)
                .orElseThrow(() -> new GenerationExecutionPolicyException(
                        "CREATE 规格调用没有活动的任务执行上下文，taskId=" + taskId));
    }

    /** 创建服务。 */
    private AiCreateSpecService createService(
            GenerationExecutionContext context,
            Duration timeout
    ) {
        context.assertCanContinue();
        if (Thread.currentThread().isInterrupted()) {
            throw new GenerationExecutionCancelledException("worker_interrupted");
        }
        context.consume(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
        return serviceFactory.createExecutionService(
                timeout,
                () -> context.consume(GenerationBudgetKind.MODEL_TURN),
                () -> context.consume(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT)
        );
    }

    private String plannedModules(CreateGenerationPlan plan, SlotGroup group) {
        List<String> modules = plan.modules().stream()
                .filter(module -> module != null && group.templateId().equals(module.templateId()))
                .map(module -> module.moduleId() + ":" + module.slotIds())
                .toList();
        if (modules.isEmpty()) {
            return group.groupId() + ":" + group.slotIds();
        }
        return String.join("; ", modules);
    }

    /** 返回{@code aggregate}分组。 */
    private SlotGroup aggregateGroup(CreateGenerationPlan plan) {
        List<String> templateIds = plan.slotGroups().stream()
                .filter(group -> group != null && StrUtil.isNotBlank(group.templateId()))
                .map(SlotGroup::templateId)
                .distinct()
                .toList();
        List<String> slotIds = plan.slotGroups().stream()
                .filter(group -> group != null)
                .flatMap(group -> group.slotIds().stream())
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        String templateId = StrUtil.blankToDefault(plan.baseTemplateId(), String.join("+", templateIds));
        return new SlotGroup("create-request-spec", templateId, "create-request", slotIds, 0);
    }

    public record SpecResult(
            boolean available,
            CreateSpec spec,
            String reason,
            CreateSpecValidationResult validation,
            boolean modelAttempted
    ) {
        /** 创建{@code Spec}结果实例并完成必要的依赖和初始状态设置。 */
        public SpecResult {
            reason = StrUtil.blankToDefault(
                    reason,
                    modelAttempted ? "ai_spec" : "local_spec_unspecified"
            );
            validation = validation == null
                    ? CreateSpecValidationResult.ok(List.of())
                    : validation;
        }

        public SpecResult(boolean available, CreateSpec spec, String reason) {
            this(available, spec, reason, CreateSpecValidationResult.ok(List.of()),
                    inferModelAttempted(reason));
        }

        public SpecResult(
                boolean available,
                CreateSpec spec,
                String reason,
                CreateSpecValidationResult validation
        ) {
            this(available, spec, reason, validation, inferModelAttempted(reason));
        }

        private static SpecResult available(
                CreateSpec spec,
                String reason,
                CreateSpecValidationResult validation,
                boolean modelAttempted
        ) {
            return new SpecResult(
                    true,
                    spec,
                    StrUtil.blankToDefault(reason, "ai_spec"),
                    validation,
                    modelAttempted
            );
        }

        public boolean modelSucceeded() {
            return modelAttempted && "ai_spec".equals(reason);
        }

        private static boolean inferModelAttempted(String reason) {
            return StrUtil.isBlank(reason) || !reason.startsWith("local_spec");
        }
    }
}
