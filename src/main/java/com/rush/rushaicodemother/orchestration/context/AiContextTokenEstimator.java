package com.rush.rushaicodemother.orchestration.context;

/** Tokenizer-backed model-input accounting used by every context producer. */
public interface AiContextTokenEstimator {

    int estimate(String text);

    String truncate(String text, int maximumTokens);

    String truncateFromEnd(String text, int maximumTokens);
}
