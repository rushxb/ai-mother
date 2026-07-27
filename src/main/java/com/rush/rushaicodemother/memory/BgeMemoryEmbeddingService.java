package com.rush.rushaicodemother.memory;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import org.springframework.stereotype.Component;

/** 局部双语嵌入模型；不需要外部嵌入 API 或秘密。 */
@Component
public class BgeMemoryEmbeddingService implements MemoryEmbeddingService {
    private static final String MODEL_ID = "BAAI/bge-small-zh-v1.5";
    private static final String MODEL_VERSION = "langchain4j-q8-1.17.2-beta27";

    private final EmbeddingModel model = new BgeSmallZhV15QuantizedEmbeddingModel();

    @Override
    public float[] embed(String text) {
        return model.embed(text == null ? "" : text).content().vector();
    }

    @Override
    public int dimension() {
        return model.dimension();
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public String modelVersion() {
        return MODEL_VERSION;
    }
}
