package com.rush.rushaicodemother.memory;

public interface MemoryEmbeddingService {
    float[] embed(String text);
    int dimension();
    String modelId();
    String modelVersion();
}
