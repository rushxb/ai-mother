package com.rush.rushaicodemother.service;

/**
 * 在向 AI 模型发送请求之前控制提示上下文预算。
 */
public interface GenerationContextCompressionService {

    String compressMemoryContext(String context);

    String compressProjectContext(String context);

    String compressFinalPrompt(String prompt);
}
