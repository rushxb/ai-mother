package com.rush.rushaicodemother.ai.prompt;

import dev.langchain4j.invocation.InvocationContext;
import org.springframework.stereotype.Component;

/** Replaces annotation prompts with the selected immutable catalog version per invocation. */
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
