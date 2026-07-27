package com.rush.rushaicodemother.memory;

/**
 * 记忆向量化服务契约。
 */
public interface MemoryEmbeddingService {
    float[] embed(String text);
    int dimension();
    String modelId();
    String modelVersion();
}
