package com.rush.rushaicodemother.service.aimodel;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.vo.AiModelConnectionTestResultVO;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** 以关闭请求/响应日志的方式执行模型连接探测。 */
@Slf4j
@Component
public class AiModelConnectionTester {

    public AiModelConnectionTestResultVO test(AiModelRuntimeConfiguration configuration) {
        try {
            OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                    .apiKey(configuration.apiKey())
                    .baseUrl(configuration.baseUrl())
                    .modelName(configuration.modelId())
                    .temperature(configuration.temperature())
                    .logRequests(false)
                    .logResponses(false);
            applyMaxTokens(builder, configuration);
            String response = builder.build()
                    .chat("Hello, this is a connection test. Reply with 'OK' only.");
            log.info("模型连接测试成功，provider={}，modelId={}",
                    configuration.provider(), configuration.modelId());
            return AiModelConnectionTestResultVO.builder()
                    .success(StrUtil.isNotBlank(response))
                    .message("模型连接测试成功")
                    .build();
        } catch (Exception exception) {
            String safeMessage = AiModelConnectionErrorMessageResolver.resolve(exception);
            log.warn("模型连接测试失败，provider={}，modelId={}，errorType={}，message={}",
                    configuration.provider(), configuration.modelId(),
                    exception.getClass().getSimpleName(), safeMessage);
            return AiModelConnectionTestResultVO.builder()
                    .success(false)
                    .message(safeMessage)
                    .build();
        }
    }

    private void applyMaxTokens(OpenAiChatModel.OpenAiChatModelBuilder builder,
                                AiModelRuntimeConfiguration configuration) {
        int maxTokens = Math.min(configuration.maxTokens(), 256);
        String provider = configuration.provider().toLowerCase(Locale.ROOT);
        String modelId = configuration.modelId().toLowerCase(Locale.ROOT);
        if (provider.equals("xiaomi") || modelId.startsWith("mimo-v2")) {
            builder.maxCompletionTokens(maxTokens);
            return;
        }
        builder.maxTokens(maxTokens);
    }
}
