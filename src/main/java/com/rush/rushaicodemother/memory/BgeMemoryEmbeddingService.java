package com.rush.rushaicodemother.memory;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import org.springframework.stereotype.Component;

/** Local bilingual embedding model; no external embedding API or secret is required. */
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
