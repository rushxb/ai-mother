package com.rush.rushaicodemother.core.handler;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.ChatHistoryService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成事件流处理入口。
 *
 * <p>具体协议由注册的 {@link GenerationStreamHandlerAdapter} 声明，调用方无需维护工程类型分支。</p>
 */
@Component
public class StreamHandlerExecutor {

    private final Map<CodeGenTypeEnum, GenerationStreamHandlerAdapter> handlersByType;

    /** 构建不可变注册表，并在启动阶段拒绝空声明或重复声明。 */
    public StreamHandlerExecutor(List<GenerationStreamHandlerAdapter> handlers) {
        if (handlers == null || handlers.isEmpty()) {
            throw new IllegalStateException("生成流处理 adapter 列表不能为空");
        }
        EnumMap<CodeGenTypeEnum, GenerationStreamHandlerAdapter> registeredHandlers =
                new EnumMap<>(CodeGenTypeEnum.class);
        for (GenerationStreamHandlerAdapter handler : handlers) {
            if (handler == null) {
                throw new IllegalStateException("生成流处理 adapter 列表不能包含 null");
            }
            Set<CodeGenTypeEnum> supportedTypes = handler.supportedCodeGenTypes();
            if (supportedTypes == null || supportedTypes.isEmpty()) {
                throw new IllegalStateException(
                        "生成流处理 adapter 必须声明至少一个工程类型: "
                                + handler.getClass().getName());
            }
            for (CodeGenTypeEnum supportedType : supportedTypes) {
                if (supportedType == null) {
                    throw new IllegalStateException(
                            "生成流处理 adapter 不能声明 null 工程类型: "
                                    + handler.getClass().getName());
                }
                GenerationStreamHandlerAdapter previous =
                        registeredHandlers.putIfAbsent(supportedType, handler);
                if (previous != null) {
                    throw new IllegalStateException(
                            "工程类型存在重复流处理 adapter: " + supportedType.getValue());
                }
            }
        }
        this.handlersByType = Map.copyOf(registeredHandlers);
    }

    /**
     * 使用工程类型对应的 adapter 处理事件流和对话历史。
     *
     * @param originFlux         原始流
     * @param chatHistoryService 聊天历史服务
     * @param appId              应用ID
     * @param loginUser          登录用户
     * @param codeGenType        代码生成类型
     * @return 处理后的公开事件流
     */
    public Flux<GenerationStreamEvent> doExecute(
            Flux<GenerationStreamEvent> originFlux,
            ChatHistoryService chatHistoryService,
            long appId,
            User loginUser,
            CodeGenTypeEnum codeGenType
    ) {
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码生成类型不能为空");
        }
        GenerationStreamHandlerAdapter handler = handlersByType.get(codeGenType);
        if (handler == null) {
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "未注册生成流处理 adapter: " + codeGenType.getValue()
            );
        }
        return handler.handle(originFlux, chatHistoryService, appId, loginUser);
    }
}
