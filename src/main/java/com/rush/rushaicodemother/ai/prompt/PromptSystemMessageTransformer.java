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

    public String transform(String defaultSystemMessage, InvocationContext invocationContext) {
        PromptRolloutSubject subject = PromptRolloutSubject.from(invocationContext);
        PromptSelection selected = promptCatalog.select(subject).orElse(null);
        if (selected == null) {
            return defaultSystemMessage;
        }
        PromptSelection source = promptCatalog.identify(defaultSystemMessage)
                .orElseThrow(() -> new IllegalStateException(
                        "AI service system prompt is bound but not registered in the prompt catalog"));
        if (!source.promptKey().equals(selected.promptKey())) {
            throw new IllegalStateException(
                    "AI service system prompt binding does not match the prompt catalog key");
        }
        return selected.content();
    }
}
