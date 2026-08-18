package com.rush.rushaicodemother.core;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.ai.AiCodeGeneratorService;
import com.rush.rushaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.rush.rushaicodemother.ai.generation.LightweightCodeGenerationExecutor;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.core.parser.CodeParserExecutor;
import com.rush.rushaicodemother.core.saver.CodeFileSaverExecutor;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentExecutionRequest;
import com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentPromptBinding;
import com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentRuntime;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationModelTurnAdmissionException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionService;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCallTimeoutException;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelCancellationScope;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelTimeoutPolicy;
import com.rush.rushaicodemother.orchestration.runtime.model.RootModelRetryExecutor;
import com.rush.rushaicodemother.orchestration.tool.GenerationApprovalRequiredException;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * AI 代码生成门面类，组合代码生成和保存功能。
 * <p>
 * 支持通过 {@link GenerationPerformanceProfile} 动态选择模型配置，
 * 实现首次生成加速和改修场景的平衡。
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    private static final String MODEL_ADMISSION_MODE = "code_generation";

    private final AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;
    private final LightweightCodeGenerationExecutor lightweightCodeGenerationExecutor;
    private final CodeParserExecutor codeParserExecutor;
    private final CodeFileSaverExecutor codeFileSaverExecutor;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationPerformanceMonitorService performanceMonitorService;
    private final RootModelRetryExecutor rootModelRetryExecutor;
    private final GenerationStageAdmissionService generationStageAdmissionService;
    private final GenerationModelTimeoutPolicy modelTimeoutPolicy;
    private final GenerationModelInvocationCancellationBridge modelCancellationBridge;
    private final GenerationAgentRuntime generationAgentRuntime;

    /**
     * 创建 AI 代码生成门面并注入生成、解析、保存及运行时治理依赖。
     *
     * @param aiCodeGeneratorServiceFactory AI 代码生成器服务工厂
     * @param lightweightCodeGenerationExecutor 轻量代码生成协议执行器
     * @param codeParserExecutor 代码解析路由器
     * @param codeFileSaverExecutor 代码文件保存路由器
     * @param generationWorkspaceService 生成工作区服务
     * @param performanceMonitorService 性能监控服务
     * @param rootModelRetryExecutor 根模型重试执行器
     * @param generationStageAdmissionService 生成阶段准入服务
     * @param modelTimeoutPolicy 模型超时策略
     * @param modelCancellationBridge 模型调用取消桥接器
     * @param generationAgentRuntime 智能体运行时
     */
    @Autowired
    public AiCodeGeneratorFacade(AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory,
                                 LightweightCodeGenerationExecutor lightweightCodeGenerationExecutor,
                                 CodeParserExecutor codeParserExecutor,
                                 CodeFileSaverExecutor codeFileSaverExecutor,
                                 GenerationWorkspaceService generationWorkspaceService,
                                 GenerationPerformanceMonitorService performanceMonitorService,
                                 RootModelRetryExecutor rootModelRetryExecutor,
                                 GenerationStageAdmissionService generationStageAdmissionService,
                                 GenerationModelTimeoutPolicy modelTimeoutPolicy,
                                 GenerationModelInvocationCancellationBridge modelCancellationBridge,
                                 GenerationAgentRuntime generationAgentRuntime) {
        this.aiCodeGeneratorServiceFactory = aiCodeGeneratorServiceFactory;
        this.lightweightCodeGenerationExecutor = Objects.requireNonNull(
                lightweightCodeGenerationExecutor, "轻量代码生成执行器不能为空");
        this.codeParserExecutor = codeParserExecutor;
        this.codeFileSaverExecutor = codeFileSaverExecutor;
        this.generationWorkspaceService = generationWorkspaceService;
        this.performanceMonitorService = performanceMonitorService;
        this.rootModelRetryExecutor = rootModelRetryExecutor;
        this.generationStageAdmissionService = generationStageAdmissionService;
        this.modelTimeoutPolicy = modelTimeoutPolicy;
        this.modelCancellationBridge = modelCancellationBridge;
        this.generationAgentRuntime = Objects.requireNonNull(
                generationAgentRuntime, "显式智能体运行时不能为空");
    }

    /**
     * 统一入口：根据类型生成并保存代码
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     * @return 保存的目录
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成类型不能为空");
        }
        AiCodeGeneratorService aiCodeGeneratorService =
                aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
        Object generatedCode = lightweightCodeGenerationExecutor.generate(
                aiCodeGeneratorService, codeGenTypeEnum, userMessage);
        return codeFileSaverExecutor.executeSaver(generatedCode, codeGenTypeEnum, appId);
    }

    /**
     * 统一入口：根据类型生成并保存代码（流式）
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     * @return 保存的目录
     */
    public Flux<GenerationStreamEvent> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        return generateAndSaveCodeStream(userMessage, codeGenTypeEnum, appId, () -> false);
    }

    /**
     * 统一入口：根据类型生成并保存代码（流式）
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     * @param cancelChecker   取消检查器
     * @return 保存的目录
     */
    public Flux<GenerationStreamEvent> generateAndSaveCodeStream(String userMessage,
                                                                 CodeGenTypeEnum codeGenTypeEnum,
                                                                 Long appId,
                                                                 BooleanSupplier cancelChecker) {
        return generateAndSaveCodeStream(userMessage, codeGenTypeEnum, appId, cancelChecker, handle -> {});
    }

    public Flux<GenerationStreamEvent> generateAndSaveCodeStream(String userMessage,
                                                                  CodeGenTypeEnum codeGenTypeEnum,
                                                                  Long appId,
                                                                  BooleanSupplier cancelChecker,
                                                                  Consumer<GenerationCancellationHandle> handleConsumer) {
        return generateAndSaveCodeStream(userMessage, codeGenTypeEnum, appId, cancelChecker, handleConsumer, null, null);
    }

    /**
     * 统一入口：根据类型生成并保存代码（流式），支持性能配置。
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     * @param appId           应用 ID
     * @param cancelChecker   取消检查器
     * @param handleConsumer  响应处理器消费者
     * @param profile         性能配置，null 表示使用默认配置
     * @return 流式事件
     */
    public Flux<GenerationStreamEvent> generateAndSaveCodeStream(String userMessage,
                                                                  CodeGenTypeEnum codeGenTypeEnum,
                                                                  Long appId,
                                                                  BooleanSupplier cancelChecker,
                                                                  Consumer<GenerationCancellationHandle> handleConsumer,
                                                                  GenerationPerformanceProfile profile) {
        return generateAndSaveCodeStream(
                userMessage, codeGenTypeEnum, appId, cancelChecker, handleConsumer, profile, null);
    }

    /**
     * 运行时感知的流入口点。编排层显式传递上下文
     * 因此任务策略可以跨 Reactor 和虚拟线程边界保留。
     */
    public Flux<GenerationStreamEvent> generateAndSaveCodeStream(String userMessage,
                                                                  CodeGenTypeEnum codeGenTypeEnum,
                                                                  Long appId,
                                                                  BooleanSupplier cancelChecker,
                                                                  Consumer<GenerationCancellationHandle> handleConsumer,
                                                                  GenerationPerformanceProfile profile,
                                                                  GenerationExecutionContext executionContext) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成类型不能为空");
        }
        Objects.requireNonNull(handleConsumer, "取消句柄消费者不能为空");
        GenerationExecutionFence executionFence = executionContext == null
                ? null
                : executionContext.executionFence();
        if (lightweightCodeGenerationExecutor.supports(codeGenTypeEnum)) {
            return processSimpleTokenStream(
                    cancellationScope -> requestSimpleTokenStream(
                            modelServiceSupplier(
                                    appId, codeGenTypeEnum, profile, executionContext),
                            codeGenTypeEnum, userMessage, cancellationScope),
                    codeGenTypeEnum,
                    appId,
                    cancelChecker,
                    handleConsumer,
                    executionContext,
                    executionFence
            );
        }
        if (!GenerationAgentPromptBinding.supports(codeGenTypeEnum)) {
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "不支持的生成类型：" + codeGenTypeEnum.getValue()
            );
        }
        if (executionContext == null || executionFence == null) {
            throw new IllegalStateException("工程项目生成必须使用受管执行上下文");
        }
        return processAgentRuntimeWithRetry(
                userMessage,
                codeGenTypeEnum,
                appId,
                cancelChecker,
                handleConsumer,
                profile,
                executionContext,
                executionFence
        );
    }

    /** 将轻量代码生成 TokenStream 转换为可取消、可监督的事件流。 */
    private Flux<GenerationStreamEvent> processSimpleTokenStream(
            Function<GenerationModelCancellationScope, TokenStream> tokenStreamSupplier,
            CodeGenTypeEnum codeGenType,
            Long appId,
            BooleanSupplier cancelChecker,
            Consumer<GenerationCancellationHandle> handleConsumer,
            GenerationExecutionContext executionContext,
            GenerationExecutionFence executionFence) {
        Flux<GenerationStreamEvent> stream = rootModelRetryExecutor.execute(() -> {
            GenerationStageAdmissionService.ModelTurnWindow attemptWindow =
                    reserveModelAttempt(executionContext);
            GenerationModelCancellationScope cancellationScope =
                    new GenerationModelCancellationScope();
            AtomicBoolean firstModelActivity = new AtomicBoolean(false);
            StringBuilder codeBuilder = new StringBuilder();
            Flux<GenerationStreamEvent> attemptStream = Flux.create(sink -> {
                AtomicReference<StreamingHandle> activeStreamingHandle = new AtomicReference<>();
                sink.onCancel(cancellationScope::cancel);
                try {
                    handleConsumer.accept(cancellationScope);
                    if (stopCancelledStream(sink, cancelChecker, cancellationScope)) {
                        return;
                    }
                    TokenStream tokenStream = Objects.requireNonNull(
                            tokenStreamSupplier.apply(cancellationScope), "模型流不能为空");
                    TokenStream configuredStream = tokenStream
                            .onPartialResponseWithContext((partialResponse, context) -> {
                                firstModelActivity.set(true);
                                registerStreamingHandle(
                                        context.streamingHandle(), activeStreamingHandle, cancellationScope);
                                if (stopCancelledStream(sink, cancelChecker, cancellationScope)) {
                                    return;
                                }
                                String text = partialResponse.text();
                                if (text != null) {
                                    codeBuilder.append(text);
                                    sink.next(GenerationStreamEvent.aiDelta(text));
                                }
                            })
                            .onPartialThinkingWithContext((partialThinking, context) -> {
                                firstModelActivity.set(true);
                                registerStreamingHandle(
                                        context.streamingHandle(), activeStreamingHandle, cancellationScope);
                                stopCancelledStream(sink, cancelChecker, cancellationScope);
                            })
                            .onPartialToolCallWithContext((partialToolCall, context) -> {
                                firstModelActivity.set(true);
                                registerStreamingHandle(
                                        context.streamingHandle(), activeStreamingHandle, cancellationScope);
                                stopCancelledStream(sink, cancelChecker, cancellationScope);
                            })
                            .onIntermediateResponse(response -> firstModelActivity.set(true))
                            .onToolExecuted(toolExecution -> firstModelActivity.set(true))
                            .onCompleteResponse(response -> {
                                firstModelActivity.set(true);
                                if (stopCancelledStream(sink, cancelChecker, cancellationScope)) {
                                    return;
                                }
                                appendCompleteResponseWhenNoDelta(codeBuilder, response);
                                try {
                                    saveSimpleGeneratedCode(
                                            codeBuilder.toString(), codeGenType, appId, executionFence);
                                    cancellationScope.complete();
                                    sink.complete();
                                } catch (RuntimeException failure) {
                                    cancellationScope.complete();
                                    log.error("保存生成代码失败，appId={}, codeGenType={}",
                                            appId, codeGenType, LogExceptionSanitizer.sanitize(failure));
                                    sink.error(new BusinessException(
                                            ErrorCode.SYSTEM_ERROR,
                                            "保存生成代码失败，请稍后重试",
                                            failure
                                    ));
                                }
                            })
                            .onError(error -> {
                                firstModelActivity.set(true);
                                if (stopCancelledStream(sink, cancelChecker, cancellationScope)) {
                                    return;
                                }
                                cancellationScope.complete();
                                sink.error(error);
                            });
                    try (GenerationModelInvocationCancellationBridge.ScopeBinding ignored =
                                 modelCancellationBridge.activate(cancellationScope)) {
                        configuredStream.start();
                    }
                } catch (RuntimeException failure) {
                    cancellationScope.cancel();
                    sink.error(failure);
                }
            });
            return applyModelAttemptTimeout(
                    attemptStream,
                    attemptWindow,
                    executionContext,
                    firstModelActivity::get
            )
                    .doOnError(ignored -> cancellationScope.cancel())
                    .doOnCancel(cancellationScope::cancel);
        }, 0, executionContext, ignored -> false);
        return observeFirstModelSignal(stream, executionContext);
    }

    /** 停止{@code Cancelled}流。 */
    private boolean stopCancelledStream(FluxSink<GenerationStreamEvent> sink,
                                        BooleanSupplier cancelChecker,
                                        GenerationModelCancellationScope cancellationScope) {
        if (sink.isCancelled()) {
            cancellationScope.cancel();
            return true;
        }
        if (cancellationScope.isCancelled()) {
            sink.complete();
            return true;
        }
        if (!isCancelled(cancelChecker)) {
            return false;
        }
        cancellationScope.cancel();
        sink.complete();
        return true;
    }

    private void appendCompleteResponseWhenNoDelta(StringBuilder codeBuilder,
                                                   ChatResponse response) {
        if (!codeBuilder.isEmpty()
                || response == null
                || response.aiMessage() == null
                || response.aiMessage().text() == null) {
            return;
        }
        codeBuilder.append(response.aiMessage().text());
    }

    private void saveSimpleGeneratedCode(String completeCode,
                                         CodeGenTypeEnum codeGenType,
                                         Long appId,
                                         GenerationExecutionFence executionFence) {
        Object parsedResult = codeParserExecutor.executeParser(completeCode, codeGenType);
        GenerationWorkspace workspace = resolveCallbackWorkspace(
                executionFence, appId, codeGenType, true);
        File saveDir = codeFileSaverExecutor.executeSaver(
                parsedResult, codeGenType, appId, workspace);
        if (saveDir == null) {
            throw new IllegalStateException("代码保存目录不能为空");
        }
        log.info("保存成功，目录为：{}", saveDir.getAbsolutePath());
    }

    /**
     * 将 TokenStream 转换为 Flux<String>，并传递工具调用信息
     *
     * @param tokenStreamSupplier TokenStream 供应器
     * @param appId               应用 ID
     * @return Flux<String> 流式响应
     */
    private Flux<GenerationStreamEvent> processAgentRuntimeWithRetry(
            String userMessage,
            CodeGenTypeEnum codeGenType,
            Long appId,
            BooleanSupplier cancelChecker,
            Consumer<GenerationCancellationHandle> handleConsumer,
            GenerationPerformanceProfile profile,
            GenerationExecutionContext executionContext,
            GenerationExecutionFence executionFence) {
        Objects.requireNonNull(generationAgentRuntime, "显式智能体运行时不能为空");
        Objects.requireNonNull(executionContext, "生成执行上下文不能为空");
        GenerationWorkspace workspace = resolveCallbackWorkspace(
                executionFence, appId, codeGenType, false);
        GenerationAgentExecutionRequest request = new GenerationAgentExecutionRequest(
                appId,
                userMessage,
                codeGenType,
                profile,
                workspace.canonicalRootPath().toString(),
                executionContext,
                cancelChecker,
                handleConsumer
        );
        int maxRetries = Math.max(
                0,
                executionContext.limit(GenerationBudgetKind.ROOT_MODEL_ATTEMPT) - 1
        );
        Flux<GenerationStreamEvent> stream = rootModelRetryExecutor.execute(() -> {
            reserveModelAttempt(executionContext);
            AtomicBoolean emittedAnyEvent = new AtomicBoolean(false);
            int initialWorkspaceMutations =
                    executionContext.successfulWorkspaceMutationCount();
            return generationAgentRuntime.start(request)
                    .doOnNext(ignored -> emittedAnyEvent.set(true))
                    .onErrorResume(error -> {
                        GenerationModelTurnAdmissionException admission =
                                findModelTurnAdmission(error);
                        if (admission == null
                                || executionContext.successfulWorkspaceMutationCount()
                                <= initialWorkspaceMutations) {
                            return Flux.error(error);
                        }
                        return completionWindowEvents(
                                codeGenType,
                                appId,
                                executionFence,
                                admission
                        );
                    })
                    .onErrorMap(error -> shouldPreventRootReplay(
                            error,
                            emittedAnyEvent.get()
                                    || hasNewWorkspaceMutations(
                                    executionContext, initialWorkspaceMutations))
                            ? new NonRetriableStreamException(error)
                            : error);
        }, maxRetries, executionContext, this::isRetriableStreamError);
        return observeFirstModelSignal(stream, executionContext);
    }

    /** 观测并记录{@code First}模型{@code Signal}。 */
    private Flux<GenerationStreamEvent> observeFirstModelSignal(
            Flux<GenerationStreamEvent> stream,
            GenerationExecutionContext executionContext
    ) {
        if (executionContext == null) {
            return stream;
        }
        return Flux.defer(() -> {
            Instant startedAt = Instant.now();
            java.util.concurrent.atomic.AtomicBoolean recorded = new java.util.concurrent.atomic.AtomicBoolean(false);
            return stream.doOnNext(event -> {
                if (!recorded.compareAndSet(false, true)) {
                    return;
                }
                Duration latency = Duration.between(startedAt, Instant.now());
                long latencyMs = Math.max(1L, latency.toMillis());
                performanceMonitorService.recordSpan(
                        executionContext.taskId(),
                        "model_time_to_first_signal",
                        GenerationSpanCategory.MODEL,
                        "success",
                        latency,
                        event == null ? "unknown" : Objects.toString(event.getType(), "unknown")
                );
                performanceMonitorService.recordRuntimeTelemetry(
                        executionContext.taskId(), Map.of("firstTokenLatencyMs", latencyMs));
            });
        });
    }

    /** 返回{@code reserve}模型尝试。 */
    private GenerationStageAdmissionService.ModelTurnWindow reserveModelAttempt(
            GenerationExecutionContext executionContext) {
        if (executionContext == null) {
            return null;
        }
        GenerationStageAdmissionService.ModelTurnWindow window =
                generationStageAdmissionService.requireModelAttemptWindow(
                        executionContext,
                        MODEL_ADMISSION_MODE
                );
        executionContext.consume(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
        return window;
    }

    /** 应用模型尝试超时。 */
    private Flux<GenerationStreamEvent> applyModelAttemptTimeout(
            Flux<GenerationStreamEvent> stream,
            GenerationStageAdmissionService.ModelTurnWindow attemptWindow,
            GenerationExecutionContext executionContext,
            BooleanSupplier firstModelActivity) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (attemptWindow == null) {
            return stream;
        }
        Duration attemptTimeout = attemptWindow.timeout();
        Duration firstSignalTimeout = modelTimeoutPolicy.firstSignalTimeout(attemptTimeout);
        BooleanSupplier activityChecker = firstModelActivity == null
                ? () -> false
                : firstModelActivity;
        Flux<GenerationStreamEvent> firstSignalGuard = Mono.delay(firstSignalTimeout)
                .filter(ignored -> !activityChecker.getAsBoolean())
                .flatMap(ignored -> Mono.<GenerationStreamEvent>error(
                        new GenerationModelCallTimeoutException("first-signal")))
                .thenMany(Flux.never());
        Flux<GenerationStreamEvent> inactivityBounded = stream.timeout(
                attemptTimeout,
                Flux.error(new GenerationModelCallTimeoutException("inactivity"))
        );
        // 空闲超时会被持续事件刷新，因此还要用独立计时器限制整回合墙钟时间。
        Mono<GenerationStreamEvent> totalTimeout = Mono.delay(attemptTimeout)
                .flatMap(ignored -> Mono.error(
                        new GenerationModelCallTimeoutException("wall-clock")));
        return inactivityBounded
                .takeUntilOther(firstSignalGuard)
                .takeUntilOther(totalTimeout)
                .onErrorMap(java.util.concurrent.TimeoutException.class, error -> {
                    if (executionContext != null && executionContext.isDeadlineExceeded()) {
                        return new GenerationDeadlineExceededException(executionContext.taskId());
                    }
                    if (executionContext != null && attemptWindow.completionWindowLimited()) {
                        return generationStageAdmissionService.completionWindowReached(
                                executionContext,
                                MODEL_ADMISSION_MODE,
                                attemptWindow
                        );
                    }
                    return error;
                });
    }

    /** 返回完成窗口事件。 */
    private Flux<GenerationStreamEvent> completionWindowEvents(
            CodeGenTypeEnum codeGenType,
            Long appId,
            GenerationExecutionFence executionFence,
            GenerationModelTurnAdmissionException admission) {
        GenerationWorkspace workspace = resolveCallbackWorkspace(
                executionFence,
                appId,
                codeGenType,
                false
        );
        log.info("模型阶段已停止扩展并转入工程校验，taskId={}, appId={}, remainingMs={}",
                executionFence == null ? null : executionFence.taskId(),
                appId,
                admission.remaining().toMillis());
        return Flux.just(
                GenerationStreamEvent.agentEvent("", Map.of(
                        "agent", "DeadlinePolicy",
                        "stage", "model_turn_admission",
                        "status", "reserved_completion",
                        "reason", "completion_window_reserved",
                        "remainingMs", admission.remaining().toMillis(),
                        "requiredMs", admission.required().toMillis(),
                        "completionReserveMs", admission.completionReserve().toMillis()
                )),
                GenerationStreamEvent.generationStage(
                        "模型阶段已收口，正在执行工程校验",
                        Map.of(
                                "status", "transition",
                                "stage", "codegen_done",
                                "reason", "completion_window_reserved",
                                "projectPath", workspace.canonicalRootPath().toString(),
                                "summary", "已保留构建、验证与发布所需时间"
                        )
                )
        );
    }

    private boolean shouldPreventRootReplay(Throwable error, boolean attemptMadeProgress) {
        return attemptMadeProgress
                && !(error instanceof NonRetriableStreamException)
                && findApprovalRequired(error) == null
                && findModelTurnAdmission(error) == null;
    }

    /** 工作区一旦发生成功变更，根模型调用不得重放，以免重复执行副作用。 */
    private boolean hasNewWorkspaceMutations(GenerationExecutionContext executionContext,
                                             int initialWorkspaceMutations) {
        return executionContext != null
                && executionContext.successfulWorkspaceMutationCount()
                > initialWorkspaceMutations;
    }

    /** 注册{@code Streaming}句柄。 */
    private void registerStreamingHandle(StreamingHandle streamingHandle,
                                         AtomicReference<StreamingHandle> activeStreamingHandle,
                                         GenerationModelCancellationScope cancellationScope) {
        if (streamingHandle == null) {
            return;
        }
        StreamingHandle previousHandle = activeStreamingHandle.getAndSet(streamingHandle);
        if (previousHandle != streamingHandle) {
            cancellationScope.register(() -> cancelStreaming(streamingHandle));
        }
    }

    /** 取消{@code Streaming}。 */
    private void cancelStreaming(StreamingHandle streamingHandle) {
        if (streamingHandle == null || streamingHandle.isCancelled()) {
            return;
        }
        try {
            streamingHandle.cancel();
        } catch (RuntimeException exception) {
            log.warn("取消活动模型流失败", LogExceptionSanitizer.sanitize(exception));
        }
    }

    /** 判断{@code Retriable}流错误是否满足约束。 */
    private boolean isRetriableStreamError(Throwable error) {
        if (error == null) {
            return false;
        }
        if (error instanceof NonRetriableStreamException) {
            return false;
        }
        if (error instanceof GenerationModelCallTimeoutException
                || error instanceof RateLimitException || error instanceof InternalServerException
                || error instanceof TimeoutException || error instanceof java.util.concurrent.TimeoutException) {
            return true;
        }
        if (error instanceof HttpException httpException) {
            int statusCode = httpException.statusCode();
            return statusCode == 429 || (statusCode >= 500 && statusCode < 600);
        }
        return false;
    }

    /** 查找匹配的审批{@code Required}。 */
    private GenerationApprovalRequiredException findApprovalRequired(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof GenerationApprovalRequiredException approvalRequired) {
                return approvalRequired;
            }
            current = current.getCause();
        }
        return null;
    }

    /** 查找匹配的模型轮次准入。 */
    private GenerationModelTurnAdmissionException findModelTurnAdmission(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof GenerationModelTurnAdmissionException admission) {
                return admission;
            }
            current = current.getCause();
        }
        return null;
    }

    private static final class NonRetriableStreamException extends RuntimeException {
        private NonRetriableStreamException(Throwable cause) {
            super(cause);
        }
    }

    private boolean isCancelled(BooleanSupplier cancelChecker) {
        return cancelChecker != null && cancelChecker.getAsBoolean();
    }

    private GenerationWorkspace resolveCallbackWorkspace(GenerationExecutionFence executionFence,
                                                          Long appId,
                                                          CodeGenTypeEnum codeGenType,
                                                          boolean prepareLegacyWorkspace) {
        if (executionFence != null) {
            return generationWorkspaceService.resolveExecution(executionFence, appId, codeGenType);
        }
        return prepareLegacyWorkspace
                ? generationWorkspaceService.prepare(appId, codeGenType)
                : generationWorkspaceService.resolve(appId, codeGenType);
    }

    /** 返回模型服务提供器。 */
    private Supplier<AiCodeGeneratorService> modelServiceSupplier(
            Long appId,
            CodeGenTypeEnum codeGenType,
            GenerationPerformanceProfile profile,
            GenerationExecutionContext executionContext) {
        if (executionContext == null) {
            return () -> aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(
                    appId, codeGenType, profile);
        }
        return () -> aiCodeGeneratorServiceFactory.createTaskScopedAiCodeGeneratorService(
                appId,
                codeGenType,
                profile,
                executionContext.limits().modelCallTimeout(),
                () -> generationStageAdmissionService.requireModelTurn(
                        executionContext,
                        MODEL_ADMISSION_MODE
                ),
                () -> executionContext.consume(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT)
        );
    }

    /** 返回请求{@code Simple}令牌流。 */
    private TokenStream requestSimpleTokenStream(
            Supplier<AiCodeGeneratorService> serviceSupplier,
            CodeGenTypeEnum codeGenType,
            String userMessage,
            GenerationModelCancellationScope cancellationScope) {
        AiCodeGeneratorService service = serviceSupplier.get();
        InvocationParameters parameters = InvocationParameters.from(
                GenerationModelCancellationScope.INVOCATION_PARAMETER,
                cancellationScope
        );
        return lightweightCodeGenerationExecutor.generateStream(
                service, codeGenType, userMessage, parameters);
    }

}
