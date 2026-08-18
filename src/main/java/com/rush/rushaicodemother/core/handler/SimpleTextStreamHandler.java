package com.rush.rushaicodemother.core.handler;

import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.ChatHistoryService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Set;

/** 处理 HTML 与多文件生成的简单文本事件流。 */
@Component
public class SimpleTextStreamHandler implements GenerationStreamHandlerAdapter {

    private static final Set<CodeGenTypeEnum> SUPPORTED_TYPES = Set.of(
            CodeGenTypeEnum.HTML,
            CodeGenTypeEnum.MULTI_FILE
    );

    @Override
    public Set<CodeGenTypeEnum> supportedCodeGenTypes() {
        return SUPPORTED_TYPES;
    }

    /**
     * 处理传统流（HTML, MULTI_FILE）
     * 直接收集完整的文本响应
     *
     * @param originFlux         原始流
     * @param chatHistoryService 聊天历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @return 处理后的流
     */
    @Override
    public Flux<GenerationStreamEvent> handle(
            Flux<GenerationStreamEvent> originFlux,
            ChatHistoryService chatHistoryService,
            long appId,
            User loginUser
    ) {
        StringBuilder aiResponseBuilder = new StringBuilder();
        return originFlux
                .map(event -> {
                    // 保留完整文本，流结束后一次性写入对话历史。
                    aiResponseBuilder.append(event.getText());
                    return event;
                })
                .doOnComplete(() -> {
                    String aiResponse = aiResponseBuilder.toString();
                    chatHistoryService.addChatMessage(
                            appId,
                            aiResponse,
                            ChatHistoryMessageTypeEnum.AI.getValue(),
                            loginUser.getId()
                    );
                })
                .doOnError(error -> {
                    // 失败时仍保存已产生的公开文本，避免用户上下文无故丢失。
                    GenerationErrorClassifier.GenerationError generationError =
                            GenerationErrorClassifier.classify(error);
                    String errorMessage = aiResponseBuilder + "\n\nAI回复失败: " + generationError.message();
                    chatHistoryService.addChatMessage(
                            appId,
                            errorMessage,
                            ChatHistoryMessageTypeEnum.AI.getValue(),
                            loginUser.getId()
                    );
                });
    }
}
