package com.rush.rushaicodemother.ai.prompt;

import dev.langchain4j.invocation.InvocationContext;
import org.springframework.stereotype.Component;

/** 将注释提示替换为每次调用所选的不可变目录版本。 */
@Component
public class PromptSystemMessageTransformer {

    private final PromptCatalog promptCatalog;

    public PromptSystemMessageTransformer(PromptCatalog promptCatalog) {
        this.promptCatalog = promptCatalog;
    }

    /**
 * 将输入转换为提示词{@code System}消息{@code Transformer}。
 *
 * @param defaultSystemMessage {@code defaultSystemMessage} 对应的调用参数
 * @param invocationContext 调用上下文
 * @return 处理后的提示词{@code System}消息{@code Transformer}文本
 */
    public String transform(String defaultSystemMessage, InvocationContext invocationContext) {
        PromptRolloutSubject subject = PromptRolloutSubject.from(invocationContext);
        PromptSelection selected = promptCatalog.select(subject).orElse(null);
        if (selected == null) {
            return defaultSystemMessage;
        }
        return requireMatchingSelection(
                defaultSystemMessage, selected.promptKey(), selected);
    }

    /** 按显式提示键应用稳定版或金丝雀版，不依赖虚构的 AI Service 方法绑定。 */
    public String transform(String promptKey,
                            String defaultSystemMessage,
                            InvocationContext invocationContext) {
        if (promptKey == null || promptKey.isBlank()) {
            throw new IllegalArgumentException("提示键不能为空");
        }
        PromptRolloutSubject subject = PromptRolloutSubject.from(invocationContext);
        PromptSelection selected = promptCatalog.selectByKey(
                promptKey.trim(), subject.cohortKey()).orElse(null);
        if (selected == null) {
            if (promptCatalog.snapshot().managed()) {
                throw new IllegalStateException("显式提示键未注册到提示目录");
            }
            return defaultSystemMessage;
        }
        return requireMatchingSelection(
                defaultSystemMessage, promptKey.trim(), selected);
    }

    private String requireMatchingSelection(String defaultSystemMessage,
                                            String expectedPromptKey,
                                            PromptSelection selected) {
        PromptSelection source = promptCatalog.identify(defaultSystemMessage)
                .orElseThrow(() -> new IllegalStateException(
                        "默认系统提示已绑定，但未注册到提示目录"));
        if (!source.promptKey().equals(expectedPromptKey)
                || !selected.promptKey().equals(expectedPromptKey)) {
            throw new IllegalStateException(
                    "系统提示绑定与提示目录键不一致");
        }
        return selected.content();
    }
}
