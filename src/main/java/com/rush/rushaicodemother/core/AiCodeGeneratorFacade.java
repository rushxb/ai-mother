package com.rush.rushaicodemother.core;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.ai.AiCodeGeneratorService;
import com.rush.rushaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.HtmlCodeResult;
import com.rush.rushaicodemother.ai.model.MultiFileCodeResult;
import com.rush.rushaicodemother.ai.model.message.ToolExecutedMessage;
import com.rush.rushaicodemother.ai.model.message.ToolRequestMessage;
import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.core.parser.CodeParserExecutor;
import com.rush.rushaicodemother.core.saver.CodeFileSaverExecutor;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.progress.ReasoningProgressTracker;
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
import com.rush.rushaicodemother.orchestration.runtime.model.RootModelRetryPolicy;
import com.rush.rushaicodemother.orchestration.tool.GenerationApprovalRequiredException;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
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

    private static final int MAX_STREAM_RETRIES = 3;
    private static final String MODEL_ADMISSION_MODE = "code_generation";

    private final AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;
    private final CodeFileSaverExecutor codeFileSaverExecutor;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationPerformanceMonitorService performanceMonitorService;
    private final RootModelRetryExecutor rootModelRetryExecutor;
    private final GenerationStageAdmissionService generationStageAdmissionService;
    private final GenerationModelTimeoutPolicy modelTimeoutPolicy;
    private final GenerationModelInvocationCancellationBridge modelCancellationBridge;

    @Autowired
    public AiCodeGeneratorFacade(AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory,
                                 CodeFileSaverExecutor codeFileSaverExecutor,
                                 GenerationWorkspaceService generationWorkspaceService,
                                 GenerationPerformanceMonitorService performanceMonitorService,
                                 RootModelRetryExecutor rootModelRetryExecutor,
                                 GenerationStageAdmissionService generationStageAdmissionService,
                                 GenerationModelTimeoutPolicy modelTimeoutPolicy,
                                 GenerationModelInvocationCancellationBridge modelCancellationBridge) {
        this.aiCodeGeneratorServiceFactory = aiCodeGeneratorServiceFactory;
        this.codeFileSaverExecutor = codeFileSaverExecutor;
        this.generationWorkspaceService = generationWorkspaceService;
        this.performanceMonitorService = performanceMonitorService;
        this.rootModelRetryExecutor = rootModelRetryExecutor;
        this.generationStageAdmissionService = generationStageAdmissionService;
        this.modelTimeoutPolicy = modelTimeoutPolicy;
        this.modelCancellationBridge = modelCancellationBridge;
    }

    public AiCodeGeneratorFacade(AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory,
                                 CodeFileSaverExecutor codeFileSaverExecutor,
                                 GenerationWorkspaceService generationWorkspaceService,
                                 GenerationPerformanceMonitorService performanceMonitorService,
                                 RootModelRetryExecutor rootModelRetryExecutor,
                                 GenerationStageAdmissionService generationStageAdmissionService) {
        this(
                aiCodeGeneratorServiceFactory,
                codeFileSaverExecutor,
                generationWorkspaceService,
                performanceMonitorService,
                rootModelRetryExecutor,
                generationStageAdmissionService,
                GenerationModelTimeoutPolicy.defaults(),
                new GenerationModelInvocationCancellationBridge()
        );
    }

    public AiCodeGeneratorFacade(AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory,
                                 CodeFileSaverExecutor codeFileSaverExecutor,
                                 GenerationWorkspaceService generationWorkspaceService,
                                 GenerationPerformanceMonitorService performanceMonitorService,
                                 AiModelRuntimeProperties runtimeProperties,
                                 AiModelMetricsCollector aiModelMetricsCollector,
                                 GenerationStageAdmissionService generationStageAdmissionService) {
        this(
                aiCodeGeneratorServiceFactory,
                codeFileSaverExecutor,
                generationWorkspaceService,
                performanceMonitorService,
                new RootModelRetryExecutor(
                        performanceMonitorService,
                         aiModelMetricsCollector,
                         new RootModelRetryPolicy(runtimeProperties)
                ),
                generationStageAdmissionService,
                new GenerationModelTimeoutPolicy(runtimeProperties),
                new GenerationModelInvocationCancellationBridge()
        );
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
        // 根据 appId 获取相应的 AI 服务实例
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                HtmlCodeResult result = aiCodeGeneratorService.generateHtmlCode(userMessage);
                yield codeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                MultiFileCodeResult result = aiCodeGeneratorService.generateMultiFileCode(userMessage);
                yield codeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
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
        Objects.requireNonNull(handleConsumer, "handleConsumer");
        GenerationExecutionFence executionFence = executionContext == null
                ? null
                : executionContext.executionFence();
        Supplier<AiCodeGeneratorService> serviceSupplier = modelServiceSupplier(
                appId, codeGenTypeEnum, profile, executionContext);
        return switch (codeGenTypeEnum) {
            case HTML -> processSimpleTokenStream(
                    cancellationScope -> requestSimpleTokenStream(
                            serviceSupplier, codeGenTypeEnum, userMessage, cancellationScope),
                    codeGenTypeEnum,
                    appId,
                    cancelChecker,
                    handleConsumer,
                    executionContext,
                    executionFence
            );
            case MULTI_FILE -> processSimpleTokenStream(
                    cancellationScope -> requestSimpleTokenStream(
                            serviceSupplier, codeGenTypeEnum, userMessage, cancellationScope),
                    codeGenTypeEnum,
                    appId,
                    cancelChecker,
                    handleConsumer,
                    executionContext,
                    executionFence
            );
            case VUE_PROJECT -> processTokenStreamWithRetry(
                    cancellationScope -> requestTokenStream(
                            serviceSupplier, codeGenTypeEnum, appId, userMessage,
                            executionFence, cancellationScope),
                    codeGenTypeEnum,
                    appId,
                    cancelChecker,
                    handleConsumer,
                    executionContext,
                    executionFence
            );
            case BACKEND_PROJECT -> processTokenStreamWithRetry(
                    cancellationScope -> requestTokenStream(
                            serviceSupplier, codeGenTypeEnum, appId, userMessage,
                            executionFence, cancellationScope),
                    codeGenTypeEnum,
                    appId,
                    cancelChecker,
                    handleConsumer,
                    executionContext,
                    executionFence
            );
            case FULL_STACK_PROJECT -> processTokenStreamWithRetry(
                    cancellationScope -> requestTokenStream(
                            serviceSupplier, codeGenTypeEnum, appId, userMessage,
                            executionFence, cancellationScope),
                    codeGenTypeEnum,
                    appId,
                    cancelChecker,
                    handleConsumer,
                    executionContext,
                    executionFence
            );
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
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
                    reserveModelAttempt(executionContext, codeGenType);
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
        Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
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
    private Flux<GenerationStreamEvent> processTokenStreamWithRetry(
                                                                    Function<GenerationModelCancellationScope,
                                                                            TokenStream> tokenStreamSupplier,
                                                                    CodeGenTypeEnum codeGenType,
                                                                    Long appId,
                                                                    BooleanSupplier cancelChecker,
                                                                    Consumer<GenerationCancellationHandle> handleConsumer,
                                                                    GenerationExecutionContext executionContext,
                                                                    GenerationExecutionFence executionFence) {
        Objects.requireNonNull(handleConsumer, "handleConsumer");
        int maxRetries = executionContext == null
                ? MAX_STREAM_RETRIES
                : Math.max(0, executionContext.limit(GenerationBudgetKind.ROOT_MODEL_ATTEMPT) - 1);
        Flux<GenerationStreamEvent> stream = rootModelRetryExecutor.execute(() -> {
            GenerationStageAdmissionService.ModelTurnWindow attemptWindow =
                    reserveModelAttempt(executionContext, codeGenType);
            AtomicBoolean emittedAnyEvent = new AtomicBoolean(false);
            AtomicBoolean firstModelActivity = new AtomicBoolean(false);
            GenerationModelCancellationScope cancellationScope =
                    new GenerationModelCancellationScope();
            int initialWorkspaceMutations = executionContext == null
                    ? 0
                    : executionContext.successfulWorkspaceMutationCount();
            ReasoningProgressTracker reasoningProgress = new ReasoningProgressTracker(
                    executionContext == null ? "" : executionContext.taskId()
            );
            Flux<GenerationStreamEvent> attemptStream = Flux.<GenerationStreamEvent>create(sink -> {
                AtomicReference<StreamingHandle> activeStreamingHandle = new AtomicReference<>();
                sink.onCancel(cancellationScope::cancel);
                try {
                    handleConsumer.accept(cancellationScope);
                    if (stopCancelledStream(sink, cancelChecker, cancellationScope)) {
                        return;
                    }
                    TokenStream tokenStream = Objects.requireNonNull(
                            tokenStreamSupplier.apply(cancellationScope), "模型流不能为空");
                    TokenStream configuredStream = tokenStream.onPartialResponseWithContext((partialResponse, context) -> {
                            firstModelActivity.set(true);
                            registerStreamingHandle(
                                    context.streamingHandle(), activeStreamingHandle, cancellationScope);
                            if (stopCancelledStream(sink, cancelChecker, cancellationScope)) {
                                return;
                            }
                            reasoningProgress.completeIfStarted().ifPresent(sink::next);
                            emittedAnyEvent.set(true);
                            sink.next(GenerationStreamEvent.aiDelta(partialResponse.text()));
                        })
                        .onPartialThinkingWithContext((partialThinking, context) -> {
                            firstModelActivity.set(true);
                            registerStreamingHandle(
                                    context.streamingHandle(), activeStreamingHandle, cancellationScope);
                            if (stopCancelledStream(sink, cancelChecker, cancellationScope)) {
                                return;
                            }
                            emittedAnyEvent.set(true);
                            reasoningProgress.startIfNeeded().ifPresent(sink::next);
                        })
                        .onPartialToolCallWithContext((partialToolCall, context) -> {
                            firstModelActivity.set(true);
                            registerStreamingHandle(
                                    context.streamingHandle(), activeStreamingHandle, cancellationScope);
                            if (stopCancelledStream(sink, cancelChecker, cancellationScope)) {
                                return;
                            }
                        })
                        .onIntermediateResponse(response -> {
                            firstModelActivity.set(true);
                            if (stopCancelledStream(sink, cancelChecker, cancellationScope)) {
                                return;
                            }
                            reasoningProgress.completeIfStarted().ifPresent(sink::next);
                            if (publishToolCallEvents(response, sink::next)) {
                                emittedAnyEvent.set(true);
                            }
                        })
                        .onToolExecuted((ToolExecution toolExecution) -> {
                            firstModelActivity.set(true);
                            if (stopCancelledStream(sink, cancelChecker, cancellationScope)) {
                                return;
                            }
                            reasoningProgress.completeIfStarted().ifPresent(sink::next);
                            recordToolExecution(executionContext, toolExecution);
                            emittedAnyEvent.set(true);
                            ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                            sink.next(GenerationStreamEvent.toolResult(
                                    toolExecution.result(),
                                    toolExecutionMetadata(toolExecutedMessage)
                            ));
                        })
                        .onCompleteResponse((ChatResponse response) -> {
                            firstModelActivity.set(true);
                            if (stopCancelledStream(sink, cancelChecker, cancellationScope)) {
                                return;
                            }
                            reasoningProgress.completeIfStarted().ifPresent(sink::next);
                            GenerationWorkspace workspace = resolveCallbackWorkspace(
                                    executionFence, appId, codeGenType, false);
                            String projectPath = workspace.canonicalRootPath().toString();
                            String summary = codeGenType == CodeGenTypeEnum.VUE_PROJECT
                                    ? "代码已生成，后台正在执行构建校验"
                                    : "代码已生成";
                            sink.next(GenerationStreamEvent.generationStage("代码生成完成", Map.of(
                                    "status", "transition",
                                    "stage", "codegen_done",
                                    "projectPath", projectPath,
                                    "summary", summary
                            )));
                            cancellationScope.complete();
                            sink.complete();
                        })
                        .onError((Throwable error) -> {
                            if (stopCancelledStream(sink, cancelChecker, cancellationScope)) {
                                return;
                            }
                            cancellationScope.complete();
                            GenerationModelTurnAdmissionException modelTurnAdmission =
                                    findModelTurnAdmission(error);
                            if (modelTurnAdmission != null) {
                                reasoningProgress.completeIfStarted().ifPresent(sink::next);
                                sink.error(modelTurnAdmission);
                                return;
                            }
                            reasoningProgress.failIfStarted().ifPresent(sink::next);
                            GenerationApprovalRequiredException approvalSignal = findApprovalRequired(error);
                            if (approvalSignal != null) {
                                sink.error(approvalSignal);
                                return;
                            }
                            log.error("{} 流式生成失败，appId: {}", codeGenType.getValue(), appId, LogExceptionSanitizer.sanitize(error));
                            GenerationApprovalRequiredException approvalRequired = findApprovalRequired(error);
                            if (approvalRequired != null) {
                                sink.error(approvalRequired);
                                return;
                            }
                            if (emittedAnyEvent.get()) {
                                sink.error(new NonRetriableStreamException(error));
                                return;
                            }
                            sink.error(error);
                        })
                        ;
                    try (GenerationModelInvocationCancellationBridge.ScopeBinding ignored =
                                 modelCancellationBridge.activate(cancellationScope)) {
                        configuredStream.start();
                    }
                } catch (RuntimeException failure) {
                    cancellationScope.cancel();
                    sink.error(failure);
                }
            });
            Flux<GenerationStreamEvent> boundedStream = applyModelAttemptTimeout(
                    attemptStream,
                    attemptWindow,
                    executionContext,
                    firstModelActivity::get
            )
                    .doOnError(ignored -> cancellationScope.cancel())
                    .doOnCancel(cancellationScope::cancel);
            return boundedStream
                    .onErrorResume(error -> {
                        GenerationModelTurnAdmissionException admission =
                                findModelTurnAdmission(error);
                        if (admission == null
                                || executionContext == null
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
                    .onErrorMap(error -> shouldPreventRootReplay(error, emittedAnyEvent.get())
                            ? new NonRetriableStreamException(error)
                            : error);
        }, maxRetries, executionContext, this::isRetriableStreamError);
        return observeFirstModelSignal(stream, executionContext);
    }

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

    private GenerationStageAdmissionService.ModelTurnWindow reserveModelAttempt(
            GenerationExecutionContext executionContext,
            CodeGenTypeEnum codeGenType) {
        if (executionContext == null) {
            return null;
        }
        GenerationStageAdmissionService.ModelTurnWindow window =
                generationStageAdmissionService.requireModelAttemptWindow(
                        executionContext,
                        codeGenType,
                        MODEL_ADMISSION_MODE
                );
        executionContext.consume(GenerationBudgetKind.ROOT_MODEL_ATTEMPT);
        return window;
    }

    private Flux<GenerationStreamEvent> applyModelAttemptTimeout(
            Flux<GenerationStreamEvent> stream,
            GenerationStageAdmissionService.ModelTurnWindow attemptWindow,
            GenerationExecutionContext executionContext,
            BooleanSupplier firstModelActivity) {
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

    private boolean shouldPreventRootReplay(Throwable error, boolean emittedAnyEvent) {
        return emittedAnyEvent
                && !(error instanceof NonRetriableStreamException)
                && findApprovalRequired(error) == null
                && findModelTurnAdmission(error) == null;
    }

    private void recordToolExecution(GenerationExecutionContext executionContext,
                                     ToolExecution toolExecution) {
        if (executionContext == null || toolExecution == null) {
            return;
        }
        ToolExecutionRequest request = toolExecution.request();
        String toolName = request == null || request.name() == null || request.name().isBlank()
                ? "unknown"
                : request.name().replaceAll("[^A-Za-z0-9_.-]", "_");
        performanceMonitorService.recordSpan(
                executionContext.taskId(),
                "tool_" + toolName,
                GenerationSpanCategory.TOOL,
                toolExecution.hasFailed() ? "failed" : "success",
                toolExecution.duration(),
                ""
        );
    }

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

    private void cancelStreaming(StreamingHandle streamingHandle) {
        if (streamingHandle == null || streamingHandle.isCancelled()) {
            return;
        }
        try {
            streamingHandle.cancel();
        } catch (RuntimeException exception) {
            log.warn("Failed to cancel active AI stream", LogExceptionSanitizer.sanitize(exception));
        }
    }

    private boolean publishToolCallEvents(ChatResponse response,
                                          Consumer<GenerationStreamEvent> eventConsumer) {
        if (response == null || response.aiMessage() == null) {
            return false;
        }
        java.util.List<ToolExecutionRequest> toolRequests = response.aiMessage().toolExecutionRequests();
        if (toolRequests == null || toolRequests.isEmpty()) {
            return false;
        }
        for (int index = 0; index < toolRequests.size(); index++) {
            ToolRequestMessage toolRequestMessage = new ToolRequestMessage(toolRequests.get(index));
            eventConsumer.accept(GenerationStreamEvent.toolCall(
                    toolRequestMessage.getName(),
                    Map.of(
                            "toolName", Objects.toString(toolRequestMessage.getName(), ""),
                            "arguments", Objects.toString(toolRequestMessage.getArguments(), ""),
                            "requestId", Objects.toString(toolRequestMessage.getId(), ""),
                            "toolIndex", index
                    )
            ));
        }
        return true;
    }

    private Map<String, Object> toolExecutionMetadata(ToolExecutedMessage toolExecutedMessage) {
        return Map.of(
                "toolName", Objects.toString(toolExecutedMessage.getName(), ""),
                "arguments", Objects.toString(toolExecutedMessage.getArguments(), ""),
                "result", Objects.toString(toolExecutedMessage.getResult(), ""),
                "requestId", Objects.toString(toolExecutedMessage.getId(), "")
        );
    }

    private boolean isRetriableStreamError(Throwable error) {
        if (error == null) {
            return false;
        }
        if (error instanceof NonRetriableStreamException) {
            return false;
        }
        if (error instanceof RateLimitException || error instanceof InternalServerException
                || error instanceof TimeoutException || error instanceof java.util.concurrent.TimeoutException) {
            return true;
        }
        if (error instanceof HttpException httpException) {
            int statusCode = httpException.statusCode();
            return statusCode == 429 || (statusCode >= 500 && statusCode < 600);
        }
        return false;
    }

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
                        codeGenType,
                        MODEL_ADMISSION_MODE
                ),
                () -> executionContext.consume(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT)
        );
    }

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
        return switch (codeGenType) {
            case HTML -> service.generateHtmlCodeStream(userMessage, parameters);
            case MULTI_FILE -> service.generateMultiFileCodeStream(userMessage, parameters);
            default -> throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "轻量流式生成类型不受支持"
            );
        };
    }

    private TokenStream requestTokenStream(Supplier<AiCodeGeneratorService> serviceSupplier,
                                            CodeGenTypeEnum codeGenType,
                                            Long appId,
                                            String userMessage,
                                            GenerationExecutionFence executionFence,
                                            GenerationModelCancellationScope cancellationScope) {
        AiCodeGeneratorService service = serviceSupplier.get();
        if (executionFence == null) {
            return switch (codeGenType) {
                case VUE_PROJECT -> service.generateVueProjectCodeStream(appId, userMessage);
                case BACKEND_PROJECT -> service.generateBackendProjectCodeStream(appId, userMessage);
                case FULL_STACK_PROJECT -> service.generateFullStackProjectCodeStream(appId, userMessage);
                default -> throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "Tool-enabled streaming generation type is unsupported");
            };
        }
        InvocationParameters parameters = InvocationParameters.from(Map.of(
                com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService
                        .EXECUTION_FENCE_PARAMETER,
                executionFence,
                GenerationModelCancellationScope.INVOCATION_PARAMETER,
                cancellationScope
        ));
        return switch (codeGenType) {
            case VUE_PROJECT -> service.generateVueProjectCodeStream(appId, userMessage, parameters);
            case BACKEND_PROJECT -> service.generateBackendProjectCodeStream(appId, userMessage, parameters);
            case FULL_STACK_PROJECT -> service.generateFullStackProjectCodeStream(appId, userMessage, parameters);
            default -> throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "Tool-enabled streaming generation type is unsupported");
        };
    }
}
