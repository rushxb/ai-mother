package com.rush.rushaicodemother.orchestration.context;

/** 每个上下文生成器都使用由分词器支持的模型输入会计。 */
public interface AiContextTokenEstimator {

    int estimate(String text);

    String truncate(String text, int maximumTokens);

    String truncateFromEnd(String text, int maximumTokens);
}
