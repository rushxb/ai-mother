package com.rush.rushaicodemother.orchestration.context;

/** 应用模型输入预算，而不将上下文生成器耦合到分词器实现。 */
public interface AiContextPackBudgeter {

    AiContextPack apply(AiContextPack contextPack);
}
