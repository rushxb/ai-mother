package com.rush.rushaicodemother.orchestration.context;

/** Applies the model-input budget without coupling context producers to a tokenizer implementation. */
public interface AiContextPackBudgeter {

    AiContextPack apply(AiContextPack contextPack);
}
