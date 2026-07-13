package com.rush.rushaicodemother.core;

import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.ai.AiCodeGeneratorService;
import com.rush.rushaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.HtmlCodeResult;
import com.rush.rushaicodemother.ai.model.MultiFileCodeResult;
import com.rush.rushaicodemother.ai.model.message.ToolExecutedMessage;
import com.rush.rushaicodemother.ai.model.message.ToolRequestMessage;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.core.parser.CodeParserExecutor;
import com.rush.rushaicodemother.core.saver.CodeFileSaverExecutor;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.io.File;
import java.time.Duration;
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
@RequiredArgsConstructor
public class AiCodeGeneratorFacade {

    private static final int MAX_STREAM_RETRIES = 3;
    private static final Duration STREAM_RETRY_MIN_DELAY = Duration.ofSeconds(3);
    private static final Duration STREAM_RETRY_MAX_DELAY = Duration.ofSeconds(20);

    private final AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

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
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                MultiFileCodeResult result = aiCodeGeneratorService.generateMultiFileCode(userMessage);
                yield CodeFileSaverExecutor.executeSaver(result, CodeGenTypeEnum.MULTI_FILE, appId);
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
        // Resolve the model service once; actual model execution remains lazy until subscription.
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum, profile);
        return switch (codeGenTypeEnum) {
            case HTML -> executeSingleModelAttempt(
                    () -> processCodeStream(
                            aiCodeGeneratorService.generateHtmlCodeStream(userMessage),
                            CodeGenTypeEnum.HTML,
                            appId,
                            cancelChecker
                    ),
                    executionContext
            );
            case MULTI_FILE -> executeSingleModelAttempt(
                    () -> processCodeStream(
                            aiCodeGeneratorService.generateMultiFileCodeStream(userMessage),
                            CodeGenTypeEnum.MULTI_FILE,
                            appId,
                            cancelChecker
                    ),
                    executionContext
            );
            case VUE_PROJECT -> processTokenStreamWithRetry(
                    () -> aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage),
                    codeGenTypeEnum,
                    appId,
                    cancelChecker,
                    handleConsumer,
                    executionContext
            );
            case BACKEND_PROJECT -> processTokenStreamWithRetry(
                    () -> aiCodeGeneratorService.generateBackendProjectCodeStream(appId, userMessage),
                    codeGenTypeEnum,
                    appId,
                    cancelChecker,
                    handleConsumer,
                    executionContext
            );
            case FULL_STACK_PROJECT -> processTokenStreamWithRetry(
                    () -> aiCodeGeneratorService.generateFullStackProjectCodeStream(appId, userMessage),
                    codeGenTypeEnum,
                    appId,
                    cancelChecker,
                    handleConsumer,
                    executionContext
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
                                                                    GenerationExecutionContext executionContext) {
        Objects.requireNonNull(handleConsumer, "handleConsumer");
        Flux<GenerationStreamEvent> modelAttempt = Flux.defer(() -> {
            Duration attemptTimeout = reserveModelAttempt(executionContext);
            java.util.concurrent.atomic.AtomicBoolean emittedAnyEvent = new java.util.concurrent.atomic.AtomicBoolean(false);
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
                            emittedAnyEvent.set(true);
                            sink.next(GenerationStreamEvent.aiDelta(partialResponse.text()));
                        })
                        .onPartialThinkingWithContext((partialThinking, context) -> {
                            registerStreamingHandle(context.streamingHandle(), activeStreamingHandle, handleConsumer);
                            if (sink.isCancelled() || isCancelled(cancelChecker)) {
                                return;
                            }
                            emittedAnyEvent.set(true);
                            sink.next(GenerationStreamEvent.aiThinkingDelta(partialThinking.text()));
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
                            if (publishToolCallEvents(response, sink::next)) {
                                emittedAnyEvent.set(true);
                            }
                        })
                        .onToolExecuted((ToolExecution toolExecution) -> {
                            if (sink.isCancelled() || isCancelled(cancelChecker)) {
                                return;
                            }
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
                            String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/" + codeGenType.getValue() + "_" + appId;
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
                            log.error("{} 流式生成失败，appId: {}", codeGenType.getValue(), appId, error);
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
        return stream.timeout(attemptTimeout)
                .onErrorMap(java.util.concurrent.TimeoutException.class, error -> {
                    if (executionContext != null && executionContext.isDeadlineExceeded()) {
                        return new GenerationDeadlineExceededException(executionContext.taskId());
                    }
                    return error;
                });
    }

    private boolean canRetryModelAttempt(GenerationExecutionContext executionContext) {
        return executionContext == null
                || executionContext.hasRemainingBudget(GenerationBudgetKind.MODEL_ATTEMPT);
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
            log.warn("Failed to cancel active AI stream", exception);
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
                                                          BooleanSupplier cancelChecker) {
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
                File saveDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
                log.info("保存成功，目录为：{}", saveDir.getAbsolutePath());
            } catch (Exception e) {
                log.error("保存生成代码失败，appId={}, codeGenType={}", appId, codeGenType, e);
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
}
