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
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.core.parser.CodeParserExecutor;
import com.rush.rushaicodemother.core.saver.CodeFileSaverExecutor;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.progress.ReasoningProgressTracker;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
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
import reactor.util.retry.Retry;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
    private static final Duration STREAM_RETRY_MIN_DELAY = Duration.ofSeconds(3);
    private static final Duration STREAM_RETRY_MAX_DELAY = Duration.ofSeconds(20);

    private final AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;
    private final CodeFileSaverExecutor codeFileSaverExecutor;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationPerformanceMonitorService performanceMonitorService;

    @Autowired
    public AiCodeGeneratorFacade(AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory,
                                 CodeFileSaverExecutor codeFileSaverExecutor,
                                 GenerationWorkspaceService generationWorkspaceService,
                                 GenerationPerformanceMonitorService performanceMonitorService) {
        this.aiCodeGeneratorServiceFactory = aiCodeGeneratorServiceFactory;
        this.codeFileSaverExecutor = codeFileSaverExecutor;
        this.generationWorkspaceService = generationWorkspaceService;
        this.performanceMonitorService = performanceMonitorService;
    }

    public AiCodeGeneratorFacade(AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory,
                                 CodeFileSaverExecutor codeFileSaverExecutor,
                                 GenerationWorkspaceService generationWorkspaceService) {
        this(aiCodeGeneratorServiceFactory, codeFileSaverExecutor, generationWorkspaceService,
                new GenerationPerformanceMonitorService(List.of()));
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
     * Runtime-aware streaming entry point. The orchestration layer passes the context explicitly
     * so task policy is preserved across Reactor and virtual-thread boundaries.
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
        return switch (codeGenTypeEnum) {
            case HTML -> executeSingleModelAttempt(
                    () -> processCodeStream(
                            aiCodeGeneratorServiceFactory
                                    .getAiCodeGeneratorService(appId, codeGenTypeEnum, profile)
                                    .generateHtmlCodeStream(userMessage),
                            CodeGenTypeEnum.HTML,
                            appId,
                            cancelChecker,
                            executionFence
                    ),
                    executionContext
            );
            case MULTI_FILE -> executeSingleModelAttempt(
                    () -> processCodeStream(
                            aiCodeGeneratorServiceFactory
                                    .getAiCodeGeneratorService(appId, codeGenTypeEnum, profile)
                                    .generateMultiFileCodeStream(userMessage),
                            CodeGenTypeEnum.MULTI_FILE,
                            appId,
                            cancelChecker,
                            executionFence
                    ),
                    executionContext
            );
            case VUE_PROJECT -> processTokenStreamWithRetry(
                    () -> requestTokenStream(
                            codeGenTypeEnum, appId, userMessage, profile, executionFence),
                    codeGenTypeEnum,
                    appId,
                    cancelChecker,
                    handleConsumer,
                    executionContext,
                    executionFence
            );
            case BACKEND_PROJECT -> processTokenStreamWithRetry(
                    () -> requestTokenStream(
                            codeGenTypeEnum, appId, userMessage, profile, executionFence),
                    codeGenTypeEnum,
                    appId,
                    cancelChecker,
                    handleConsumer,
                    executionContext,
                    executionFence
            );
            case FULL_STACK_PROJECT -> processTokenStreamWithRetry(
                    () -> requestTokenStream(
                            codeGenTypeEnum, appId, userMessage, profile, executionFence),
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

    /**
     * 将 TokenStream 转换为 Flux<String>，并传递工具调用信息
     *
     * @param tokenStreamSupplier TokenStream 供应器
     * @param appId               应用 ID
     * @return Flux<String> 流式响应
     */
    private Flux<GenerationStreamEvent> processTokenStreamWithRetry(Supplier<TokenStream> tokenStreamSupplier,
                                                                    CodeGenTypeEnum codeGenType,
                                                                    Long appId,
                                                                    BooleanSupplier cancelChecker,
                                                                    Consumer<GenerationCancellationHandle> handleConsumer,
                                                                    GenerationExecutionContext executionContext,
                                                                    GenerationExecutionFence executionFence) {
        Objects.requireNonNull(handleConsumer, "handleConsumer");
        Flux<GenerationStreamEvent> modelAttempt = Flux.defer(() -> {
            Duration attemptTimeout = reserveModelAttempt(executionContext);
            java.util.concurrent.atomic.AtomicBoolean emittedAnyEvent = new java.util.concurrent.atomic.AtomicBoolean(false);
            ReasoningProgressTracker reasoningProgress = new ReasoningProgressTracker(
                    executionContext == null ? "" : executionContext.taskId()
            );
            Flux<GenerationStreamEvent> attemptStream = Flux.<GenerationStreamEvent>create(sink -> {
                AtomicReference<StreamingHandle> activeStreamingHandle = new AtomicReference<>();
                sink.onCancel(() -> cancelStreaming(activeStreamingHandle.get()));
                if (isCancelled(cancelChecker)) {
                    sink.complete();
                    return;
                }
                TokenStream tokenStream = tokenStreamSupplier.get();
                TokenStream configuredStream = tokenStream.onPartialResponseWithContext((partialResponse, context) -> {
                            registerStreamingHandle(context.streamingHandle(), activeStreamingHandle, handleConsumer);
                            if (sink.isCancelled() || isCancelled(cancelChecker)) {
                                return;
                            }
                            reasoningProgress.completeIfStarted().ifPresent(sink::next);
                            emittedAnyEvent.set(true);
                            sink.next(GenerationStreamEvent.aiDelta(partialResponse.text()));
                        })
                        .onPartialThinkingWithContext((partialThinking, context) -> {
                            registerStreamingHandle(context.streamingHandle(), activeStreamingHandle, handleConsumer);
                            if (sink.isCancelled() || isCancelled(cancelChecker)) {
                                return;
                            }
                            emittedAnyEvent.set(true);
                            reasoningProgress.startIfNeeded().ifPresent(sink::next);
                        })
                        .onPartialToolCallWithContext((partialToolCall, context) -> {
                            registerStreamingHandle(context.streamingHandle(), activeStreamingHandle, handleConsumer);
                            if (sink.isCancelled() || isCancelled(cancelChecker)) {
                                return;
                            }
                        })
                        .onIntermediateResponse(response -> {
                            if (sink.isCancelled() || isCancelled(cancelChecker)) {
                                return;
                            }
                            reasoningProgress.completeIfStarted().ifPresent(sink::next);
                            if (publishToolCallEvents(response, sink::next)) {
                                emittedAnyEvent.set(true);
                            }
                        })
                        .onToolExecuted((ToolExecution toolExecution) -> {
                            if (sink.isCancelled() || isCancelled(cancelChecker)) {
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
                            if (sink.isCancelled() || isCancelled(cancelChecker)) {
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
                            sink.complete();
                        })
                        .onError((Throwable error) -> {
                            if (sink.isCancelled() || isCancelled(cancelChecker)) {
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
                configuredStream.start();
            });
            return applyModelAttemptTimeout(attemptStream, attemptTimeout, executionContext);
        });

        int maxRetries = executionContext == null
                ? MAX_STREAM_RETRIES
                : Math.max(0, executionContext.limit(GenerationBudgetKind.MODEL_ATTEMPT) - 1);
        if (maxRetries == 0) {
            return modelAttempt;
        }
        return modelAttempt.retryWhen(Retry.backoff(maxRetries, STREAM_RETRY_MIN_DELAY)
                .maxBackoff(STREAM_RETRY_MAX_DELAY)
                .jitter(0.35)
                .filter(error -> isRetriableStreamError(error) && canRetryModelAttempt(executionContext))
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> retrySignal.failure()));
    }

    private Flux<GenerationStreamEvent> executeSingleModelAttempt(
            Supplier<Flux<GenerationStreamEvent>> streamSupplier,
            GenerationExecutionContext executionContext) {
        return Flux.defer(() -> {
            Duration attemptTimeout = reserveModelAttempt(executionContext);
            Flux<GenerationStreamEvent> stream = Objects.requireNonNull(
                    streamSupplier.get(), "模型流不能为空");
            return applyModelAttemptTimeout(stream, attemptTimeout, executionContext);
        });
    }

    private Duration reserveModelAttempt(GenerationExecutionContext executionContext) {
        if (executionContext == null) {
            return null;
        }
        Duration timeout = executionContext.clampTimeout(executionContext.limits().modelCallTimeout());
        executionContext.consume(GenerationBudgetKind.MODEL_ATTEMPT);
        return timeout;
    }

    private Flux<GenerationStreamEvent> applyModelAttemptTimeout(
            Flux<GenerationStreamEvent> stream,
            Duration attemptTimeout,
            GenerationExecutionContext executionContext) {
        if (attemptTimeout == null) {
            return stream;
        }
        // Flux.timeout(Duration) is an inactivity timeout: a model that keeps emitting tokens
        // can otherwise run forever.  Race the stream against an explicit wall-clock deadline
        // while retaining the inactivity guard for stalled providers.
        Flux<GenerationStreamEvent> totalTimeout = Flux.defer(() ->
                Flux.<GenerationStreamEvent>error(
                        new java.util.concurrent.TimeoutException("model attempt wall-clock timeout"))
        ).delaySubscription(attemptTimeout);
        return Flux.firstWithSignal(stream.timeout(attemptTimeout), totalTimeout)
                .onErrorMap(java.util.concurrent.TimeoutException.class, error -> {
                    if (executionContext != null && executionContext.isDeadlineExceeded()) {
                        return new GenerationDeadlineExceededException(executionContext.taskId());
                    }
                    return error;
                });
    }

    private boolean canRetryModelAttempt(GenerationExecutionContext executionContext) {
        if (executionContext == null) {
            return true;
        }
        if (!executionContext.hasRemainingBudget(GenerationBudgetKind.MODEL_ATTEMPT)) {
            return false;
        }
        // Reactor's backoff is real task time.  Do not schedule a retry when the task
        // cannot afford the backoff plus even the minimum useful operation window.
        Duration retryWindow = STREAM_RETRY_MIN_DELAY
                .plus(executionContext.limits().minimumOperationTimeout());
        return executionContext.remainingDuration().compareTo(retryWindow) >= 0;
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
                                         Consumer<GenerationCancellationHandle> handleConsumer) {
        if (streamingHandle == null) {
            return;
        }
        StreamingHandle previousHandle = activeStreamingHandle.getAndSet(streamingHandle);
        if (previousHandle != streamingHandle) {
            handleConsumer.accept(() -> cancelStreaming(streamingHandle));
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

    private static final class NonRetriableStreamException extends RuntimeException {
        private NonRetriableStreamException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * 通用流式代码处理方法
     *
     * @param codeStream  代码流
     * @param codeGenType 代码生成类型
     * @param appId       应用 ID
     * @return 流式响应
     */
    private Flux<GenerationStreamEvent> processCodeStream(Flux<String> codeStream,
                                                          CodeGenTypeEnum codeGenType,
                                                          Long appId,
                                                          BooleanSupplier cancelChecker,
                                                          GenerationExecutionFence executionFence) {
        // 字符串拼接器，用于当流式返回所有的代码之后，再保存代码
        StringBuilder codeBuilder = new StringBuilder();
        return codeStream.doOnNext(chunk -> {
            throwIfCancelled(cancelChecker);
            // 实时收集代码片段
            codeBuilder.append(chunk);
        }).doOnComplete(() -> {
            if (isCancelled(cancelChecker)) {
                return;
            }
            // 流式返回完成后，保存代码
            try {
                String completeCode = codeBuilder.toString();
                // 使用执行器解析代码
                Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
                // 使用执行器保存代码
                GenerationWorkspace workspace = resolveCallbackWorkspace(
                        executionFence, appId, codeGenType, true);
                File saveDir = codeFileSaverExecutor.executeSaver(
                        parsedResult, codeGenType, appId, workspace);
                log.info("保存成功，目录为：{}", saveDir.getAbsolutePath());
            } catch (Exception e) {
                log.error("保存生成代码失败，appId={}, codeGenType={}", appId, codeGenType, LogExceptionSanitizer.sanitize(e));
                throw new BusinessException(
                        ErrorCode.SYSTEM_ERROR,
                        "保存生成代码失败，请稍后重试",
                        e
                );
            }
        }).map(chunk -> {
            throwIfCancelled(cancelChecker);
            return GenerationStreamEvent.aiDelta(chunk);
        });
    }

    private void throwIfCancelled(BooleanSupplier cancelChecker) {
        if (isCancelled(cancelChecker)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成已停止");
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

    private TokenStream requestTokenStream(CodeGenTypeEnum codeGenType,
                                           Long appId,
                                           String userMessage,
                                           GenerationPerformanceProfile profile,
                                           GenerationExecutionFence executionFence) {
        AiCodeGeneratorService service = aiCodeGeneratorServiceFactory
                .getAiCodeGeneratorService(appId, codeGenType, profile);
        if (executionFence == null) {
            return switch (codeGenType) {
                case VUE_PROJECT -> service.generateVueProjectCodeStream(appId, userMessage);
                case BACKEND_PROJECT -> service.generateBackendProjectCodeStream(appId, userMessage);
                case FULL_STACK_PROJECT -> service.generateFullStackProjectCodeStream(appId, userMessage);
                default -> throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "Tool-enabled streaming generation type is unsupported");
            };
        }
        InvocationParameters parameters = InvocationParameters.from(
                com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService
                        .EXECUTION_FENCE_PARAMETER,
                executionFence);
        return switch (codeGenType) {
            case VUE_PROJECT -> service.generateVueProjectCodeStream(appId, userMessage, parameters);
            case BACKEND_PROJECT -> service.generateBackendProjectCodeStream(appId, userMessage, parameters);
            case FULL_STACK_PROJECT -> service.generateFullStackProjectCodeStream(appId, userMessage, parameters);
            default -> throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "Tool-enabled streaming generation type is unsupported");
        };
    }
}
