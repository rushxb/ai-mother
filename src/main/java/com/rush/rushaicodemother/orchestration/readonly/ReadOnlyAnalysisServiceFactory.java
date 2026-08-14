package com.rush.rushaicodemother.orchestration.readonly;

import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.ai.prompt.PromptSystemMessageTransformer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Objects;

/** 创建不携带工具的任务级只读分析 AI 服务。 */
@Configuration
class ReadOnlyAnalysisServiceFactory {

    private final StreamingModelFactory streamingModelFactory;
    private final PromptSystemMessageTransformer promptTransformer;

    ReadOnlyAnalysisServiceFactory(StreamingModelFactory streamingModelFactory,
                                   PromptSystemMessageTransformer promptTransformer) {
        this.streamingModelFactory = Objects.requireNonNull(
                streamingModelFactory, "模型工厂不能为空");
        this.promptTransformer = Objects.requireNonNull(
                promptTransformer, "系统提示词转换器不能为空");
    }

    ReadOnlyAnalysisAiService create(Duration timeout,
                                     Runnable beforeModelTurn,
                                     Runnable beforeProviderFailoverAttempt) {
        ChatModel model = streamingModelFactory.createExecutionAnalysisChatModel(
                timeout, beforeModelTurn, beforeProviderFailoverAttempt);
        return AiServices.builder(ReadOnlyAnalysisAiService.class)
                .chatModel(model)
                .systemMessageTransformer(promptTransformer::transform)
                .build();
    }
}
