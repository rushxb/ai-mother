package com.rush.rushaicodemother.orchestration.intent;

import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.DefaultGenerationSlaPolicy;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaProperties;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationSynchronousModelCallSupervisor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 意图澄清精化器回归。
 *
 * <p>覆盖三条不可退让的约束：门禁关闭时不得调模型、模型不得下调安全边界、
 * 澄清失败必须沿用本地画像而不是让任务失败。</p>
 */
class IntentClarificationRefinerTest {

    private IntentClarificationServiceFactory serviceFactory;
    private IntentClarificationService clarificationService;
    private AiModelRuntimeProperties runtimeProperties;
    private IntentClarificationRefiner refiner;
    private GenerationSynchronousModelCallSupervisor modelCallSupervisor;

    @BeforeEach
    void setUp() {
        serviceFactory = mock(IntentClarificationServiceFactory.class);
        clarificationService = mock(IntentClarificationService.class);
        runtimeProperties = new AiModelRuntimeProperties();
        runtimeProperties.setIntentClarificationEnabled(true);
        when(serviceFactory.createExecutionIntentClarificationService(any(), any(), any()))
                .thenReturn(clarificationService);
        modelCallSupervisor = new GenerationSynchronousModelCallSupervisor();
        refiner = new IntentClarificationRefiner(
                serviceFactory, runtimeProperties, new GenerationPerformanceMonitorService(),
                modelCallSupervisor);
    }

    @AfterEach
    void closeModelCallSupervisor() {
        modelCallSupervisor.close();
    }

    @Test
    void disabledFlagMustNotSpendModelBudget() {
        runtimeProperties.setIntentClarificationEnabled(false);
        GenerationExecutionContext context = context();
        IntentProfile profile = ambiguousProfile();

        IntentProfile result = refiner.refine(profile, "把登录页做得好看点", "task-disabled", context);

        assertSame(profile, result);
        verifyNoInteractions(serviceFactory);
        assertEquals(0, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
    }

    @Test
    void resolvedProfileMustNotSpendModelBudget() {
        GenerationExecutionContext context = context();
        IntentProfile profile = ambiguousProfile().withAmbiguitySignal(IntentAmbiguitySignal.resolved());

        IntentProfile result = refiner.refine(profile, "把首页按钮颜色改成蓝色", "task-resolved", context);

        assertSame(profile, result);
        verifyNoInteractions(serviceFactory);
        assertEquals(0, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
    }

    @Test
    void clarificationMustAdoptUnresolvedDimensionsAndChargeBudget() {
        GenerationExecutionContext context = context();
        IntentClarification clarification = new IntentClarification();
        clarification.setOperationType(IntentOperationType.REPAIR);
        clarification.setSemanticComplexity(IntentSemanticComplexity.LOW);
        clarification.setExpectedFileCount(1);
        when(clarificationService.clarify(anyString(), anyString())).thenReturn(clarification);

        IntentProfile result = refiner.refine(
                ambiguousProfile(), "把登录页做得好看点", "task-adopt", context);

        assertEquals(IntentOperationType.REPAIR, result.operationType());
        assertEquals(IntentSemanticComplexity.LOW, result.semanticComplexity());
        assertEquals(1, result.expectedFileCount());
        assertFalse(result.ambiguitySignal().ambiguous(),
                "模型已经补齐的维度不得在 worker 中再次触发澄清");
        assertEquals(1, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
    }

    @Test
    void clarificationMustNotDowngradeSafetyBoundaries() {
        GenerationExecutionContext context = context();
        IntentClarification clarification = new IntentClarification();
        clarification.setSemanticComplexity(IntentSemanticComplexity.LOW);
        clarification.setExpectedFileCount(1);
        when(clarificationService.clarify(anyString(), anyString())).thenReturn(clarification);
        IntentProfile highRiskProfile = new IntentProfile(
                IntentOperationType.EDIT,
                Set.of(IntentAffectedScope.DATABASE),
                IntentSemanticComplexity.MEDIUM,
                true,
                true,
                IntentDestructiveRisk.HIGH,
                8,
                IntentValidationRisk.HIGH,
                0.8,
                twoDimensionAmbiguity()
        );

        IntentProfile result = refiner.refine(
                highRiskProfile, "删除所有旧的商品数据表并简化一下", "task-safety", context);

        assertEquals(IntentDestructiveRisk.HIGH, result.destructiveRisk(),
                "模型不得下调破坏性风险");
        assertEquals(IntentValidationRisk.HIGH, result.validationRisk(),
                "模型不得下调验证等级");
        assertTrue(result.requiresDatabase(), "模型不得撤销数据库影响判定");
    }

    @Test
    void clarificationMustNotReclassifyExistingWorkspaceAsCreate() {
        GenerationExecutionContext context = context();
        IntentClarification clarification = new IntentClarification();
        clarification.setOperationType(IntentOperationType.CREATE);
        when(clarificationService.clarify(anyString(), anyString())).thenReturn(clarification);

        IntentProfile result = refiner.refine(
                ambiguousProfile(), "把登录页做得好看点", "task-create-guard", context);

        assertEquals(IntentOperationType.EDIT, result.operationType(),
                "CREATE 由工作区状态唯一决定");
    }

    @Test
    void clarificationFailureMustFallBackToLocalProfile() {
        GenerationExecutionContext context = context();
        when(clarificationService.clarify(anyString(), anyString()))
                .thenThrow(new RuntimeException("模型不可用"));
        IntentProfile profile = ambiguousProfile();

        IntentProfile result = refiner.refine(profile, "把登录页做得好看点", "task-degrade", context);

        assertSame(profile, result, "澄清失败必须沿用本地画像，不能让任务失败");
        assertEquals(1, context.used(GenerationBudgetKind.ROOT_MODEL_ATTEMPT),
                "已发起的调用仍应计入预算");
    }

    @Test
    void clarificationMustStopAtTaskDeadlineEvenWhenProviderIgnoresInterrupt() throws Exception {
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(clarificationService.clarify(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    providerEntered.countDown();
                    awaitIgnoringInterruption(releaseProvider);
                    return new IntentClarification();
                });
        GenerationExecutionContext context = shortDeadlineContext();
        ExecutorService preflightWorker = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> outcome = preflightWorker.submit(() -> {
                try {
                    refiner.refine(
                            ambiguousProfile(), "把登录页做得好看点", "task-deadline", context);
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });

            assertTrue(providerEntered.await(1, TimeUnit.SECONDS));
            Throwable failure = outcome.get(2, TimeUnit.SECONDS);
            assertInstanceOf(GenerationDeadlineExceededException.class, failure);
        } finally {
            releaseProvider.countDown();
            preflightWorker.shutdownNow();
        }
    }

    @Test
    void oversizedFileCountMustBeClamped() {
        GenerationExecutionContext context = context();
        IntentClarification clarification = new IntentClarification();
        clarification.setExpectedFileCount(9_999);
        when(clarificationService.clarify(anyString(), anyString())).thenReturn(clarification);

        IntentProfile result = refiner.refine(
                ambiguousProfile(), "重构整个订单模块，拆分服务层", "task-clamp", context);

        assertTrue(result.expectedFileCount() <= 60, "异常文件数必须被收敛");
    }

    private IntentProfile ambiguousProfile() {
        return new IntentProfile(
                IntentOperationType.EDIT,
                Set.of(IntentAffectedScope.AUTHENTICATION),
                IntentSemanticComplexity.MEDIUM,
                true,
                false,
                IntentDestructiveRisk.LOW,
                4,
                IntentValidationRisk.HIGH,
                0.8,
                twoDimensionAmbiguity()
        );
    }

    private IntentAmbiguitySignal twoDimensionAmbiguity() {
        return new IntentAmbiguitySignal(
                Set.of(IntentResolutionDimension.OPERATION_TYPE,
                        IntentResolutionDimension.SEMANTIC_COMPLEXITY,
                        IntentResolutionDimension.EXPECTED_FILE_COUNT),
                false,
                false
        );
    }

    /** 使用真实 SLA 策略产出的预算，避免测试自造一份与生产不一致的额度表。 */
    private GenerationExecutionContext context() {
        GenerationModeDecision decision = GenerationModeDecision.of(
                GenerationMode.AGENT_EDIT,
                0.8,
                "clarification-test",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD
        );
        GenerationExecutionLimits limits = new DefaultGenerationSlaPolicy(new GenerationSlaProperties())
                .resolve(decision, CodeGenTypeEnum.VUE_PROJECT)
                .toLimits();
        return new GenerationExecutionContext(
                "task-clarify-test", 1L, 2L, Instant.now(), limits, Clock.systemUTC());
    }

    private GenerationExecutionContext shortDeadlineContext() {
        GenerationExecutionLimits baseline = context().limits();
        GenerationExecutionLimits limits = new GenerationExecutionLimits(
                Duration.ofMillis(400),
                Duration.ofMillis(300),
                Duration.ofMillis(50),
                baseline.budgets());
        return new GenerationExecutionContext(
                "task-clarify-deadline", 1L, 2L, Instant.now(), limits, Clock.systemUTC());
    }

    private static void awaitIgnoringInterruption(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
