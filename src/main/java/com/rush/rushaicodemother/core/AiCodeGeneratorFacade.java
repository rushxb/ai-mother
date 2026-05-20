package com.rush.rushaicodemother.core;

import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.ai.AiCodeGeneratorService;
import com.rush.rushaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.rush.rushaicodemother.ai.model.HtmlCodeResult;
import com.rush.rushaicodemother.ai.model.MultiFileCodeResult;
import com.rush.rushaicodemother.ai.model.message.AiResponseMessage;
import com.rush.rushaicodemother.ai.model.message.ToolExecutedMessage;
import com.rush.rushaicodemother.ai.model.message.ToolRequestMessage;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.core.parser.CodeParserExecutor;
import com.rush.rushaicodemother.core.saver.CodeFileSaverExecutor;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.ResponseHandle;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * AI 代码生成门面类，组合代码生成和保存功能
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

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
                                                                 java.util.function.Consumer<ResponseHandle> handleConsumer) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成类型不能为空");
        }
        // 根据 appId 获取相应的 AI 服务实例
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateHtmlCodeStream(userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.HTML, appId, cancelChecker);
            }
            case MULTI_FILE -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(userMessage);
                yield processCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId, cancelChecker);
            }
            case VUE_PROJECT -> {
                TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage);
                yield processTokenStream(tokenStream, codeGenTypeEnum, appId, cancelChecker, handleConsumer);
            }
            case BACKEND_PROJECT -> {
                TokenStream tokenStream = aiCodeGeneratorService.generateBackendProjectCodeStream(appId, userMessage);
                yield processTokenStream(tokenStream, codeGenTypeEnum, appId, cancelChecker, handleConsumer);
            }
            case FULL_STACK_PROJECT -> {
                TokenStream tokenStream = aiCodeGeneratorService.generateFullStackProjectCodeStream(appId, userMessage);
                yield processTokenStream(tokenStream, codeGenTypeEnum, appId, cancelChecker, handleConsumer);
            }
            default -> {
                String errorMessage = "不支持的生成类型：" + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }

    /**
     * 将 TokenStream 转换为 Flux<String>，并传递工具调用信息
     *
     * @param tokenStream TokenStream 对象
     * @param appId       应用 ID
     * @return Flux<String> 流式响应
     */
    private Flux<GenerationStreamEvent> processTokenStream(TokenStream tokenStream,
                                                           CodeGenTypeEnum codeGenType,
                                                           Long appId,
                                                           BooleanSupplier cancelChecker,
                                                           java.util.function.Consumer<ResponseHandle> handleConsumer) {
        return Flux.create(sink -> {
            TokenStream configuredStream = tokenStream.onPartialResponse((String partialResponse) -> {
                        if (sink.isCancelled() || isCancelled(cancelChecker)) {
                            return;
                        }
                        AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                        sink.next(GenerationStreamEvent.aiDelta(partialResponse));
                    })
                    .onPartialThinking((String partialThinking) -> {
                        if (sink.isCancelled() || isCancelled(cancelChecker)) {
                            return;
                        }
                        sink.next(GenerationStreamEvent.aiThinkingDelta(partialThinking));
                    })
                    .onPartialToolExecutionRequest((index, toolExecutionRequest) -> {
                        if (sink.isCancelled() || isCancelled(cancelChecker)) {
                            return;
                        }
                        ToolRequestMessage toolRequestMessage = new ToolRequestMessage(toolExecutionRequest);
                        sink.next(GenerationStreamEvent.toolCall(toolRequestMessage.getName(), java.util.Map.of(
                                "toolName", toolRequestMessage.getName(),
                                "arguments", toolRequestMessage.getArguments(),
                                "requestId", toolRequestMessage.getId(),
                                "toolIndex", index
                        )));
                    })
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        if (sink.isCancelled() || isCancelled(cancelChecker)) {
                            return;
                        }
                        ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                        sink.next(GenerationStreamEvent.toolResult(toolExecution.result(), java.util.Map.of(
                                "toolName", toolExecutedMessage.getName(),
                                "arguments", toolExecutedMessage.getArguments(),
                                "result", toolExecution.result(),
                                "requestId", toolExecutedMessage.getId()
                        )));
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
                        sink.error(error);
                    })
                    ;
            ResponseHandle handle = configuredStream.startWithHandle();
            handleConsumer.accept(handle);
        });
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
                log.error("保存失败: {}", e.getMessage());
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存失败: " + e.getMessage());
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
