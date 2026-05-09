package com.yupi.yuaicodemother.core;

import cn.hutool.json.JSONUtil;
import com.yupi.yuaicodemother.ai.AiCodeGeneratorService;
import com.yupi.yuaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.yupi.yuaicodemother.ai.model.HtmlCodeResult;
import com.yupi.yuaicodemother.ai.model.MultiFileCodeResult;
import com.yupi.yuaicodemother.ai.model.message.AiResponseMessage;
import com.yupi.yuaicodemother.ai.model.message.BuildResultMessage;
import com.yupi.yuaicodemother.ai.model.message.ToolExecutedMessage;
import com.yupi.yuaicodemother.ai.model.message.ToolRequestMessage;
import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.core.handler.GenerationStreamEvent;
import com.yupi.yuaicodemother.core.builder.VueProjectBuilder;
import com.yupi.yuaicodemother.core.parser.CodeParserExecutor;
import com.yupi.yuaicodemother.core.saver.CodeFileSaverExecutor;
import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.ResponseHandle;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.function.BooleanSupplier;

/**
 * AI 代码生成门面类，组合代码生成和保存功能
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Resource
    private VueProjectBuilder vueProjectBuilder;

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
                yield processTokenStream(tokenStream, appId, cancelChecker, handleConsumer);
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
                                "requestId", toolRequestMessage.getId()
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
                        // 执行 Vue 项目构建（同步执行，确保预览时项目已就绪）
                        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + "/vue_project_" + appId;
                        VueProjectBuilder.BuildResult buildResult = vueProjectBuilder.buildProjectWithResult(projectPath);
                        if (sink.isCancelled() || isCancelled(cancelChecker)) {
                            sink.complete();
                            return;
                        }
                        BuildResultMessage buildResultMessage = new BuildResultMessage(buildResult);
                        sink.next(GenerationStreamEvent.buildResult(buildResult.toDiagnosticReport(), java.util.Map.of(
                                "success", buildResult.success(),
                                "stage", buildResult.stage(),
                                "projectPath", buildResult.projectPath(),
                                "summary", buildResult.summary(),
                                "report", buildResult.toDiagnosticReport()
                        )));
                        if (!buildResult.success()) {
                            log.warn("Vue 项目生成后自动构建失败，appId: {}, summary: {}", appId, buildResult.summary());
                            sink.error(new BusinessException(ErrorCode.SYSTEM_ERROR, buildResult.toFailureSummary()));
                            return;
                        }
                        sink.complete();
                    })
                    .onError((Throwable error) -> {
                        if (sink.isCancelled() || isCancelled(cancelChecker)) {
                            return;
                        }
                        log.error("Vue 项目流式生成失败，appId: {}", appId, error);
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
