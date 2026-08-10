package com.rush.rushaicodemother.orchestration.intent;

import com.rush.rushaicodemother.ai.model.StreamingModelFactory;
import com.rush.rushaicodemother.ai.prompt.PromptSystemMessageTransformer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 意图澄清服务工厂。
 *
 * <p>复用路由档模型：澄清任务只产出三个枚举/数值字段，不生成代码，
 * 因此走与类型路由相同的小模型与短超时配置。</p>
 */
@Configuration
@RequiredArgsConstructor
public class IntentClarificationServiceFactory {

    private final StreamingModelFactory streamingModelFactory;
    private final PromptSystemMessageTransformer promptSystemMessageTransformer;

    /**
     * 创建受任务执行预算约束的澄清服务。
     *
     * @param timeout 调用方总超时
     * @param beforeModelTurn 每轮模型调用前执行的回调
     * @param beforeProviderFailoverAttempt 模型提供方故障转移前执行的回调
     * @return 意图澄清服务
     */
    public IntentClarificationService createExecutionIntentClarificationService(
            Duration timeout,
            Runnable beforeModelTurn,
            Runnable beforeProviderFailoverAttempt) {
        ChatModel chatModel = streamingModelFactory.createExecutionRoutingChatModel(
                timeout, beforeModelTurn, beforeProviderFailoverAttempt);
        return createService(chatModel);
    }

    private IntentClarificationService createService(ChatModel chatModel) {
        return AiServices.builder(IntentClarificationService.class)
                .chatModel(chatModel)
                .systemMessageTransformer(promptSystemMessageTransformer::transform)
                .build();
    }
}
