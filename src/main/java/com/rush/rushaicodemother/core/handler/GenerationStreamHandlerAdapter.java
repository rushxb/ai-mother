package com.rush.rushaicodemother.core.handler;

import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.ChatHistoryService;
import reactor.core.publisher.Flux;

import java.util.Set;

/**
 * 生成事件流处理 adapter。
 *
 * <p>每个 adapter 声明自己理解的工程类型，并负责将原始事件流转换为公开事件流和对话历史。</p>
 */
public interface GenerationStreamHandlerAdapter {

    /** 返回当前 adapter 支持的工程类型。 */
    Set<CodeGenTypeEnum> supportedCodeGenTypes();

    /** 处理生成事件流，并在流终止时记录对应的对话历史。 */
    Flux<GenerationStreamEvent> handle(
            Flux<GenerationStreamEvent> originFlux,
            ChatHistoryService chatHistoryService,
            long appId,
            User loginUser
    );
}
