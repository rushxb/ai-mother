package com.rush.rushaicodemother.service;

/**
 * Controls prompt context budgets before sending requests to AI models.
 */
public interface GenerationContextCompressionService {

    String compressMemoryContext(String context);

    String compressProjectContext(String context);

    String compressFinalPrompt(String prompt);
}
