package com.rush.rushaicodemother.monitor;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Provider usage 缺失时的模型用量估算 seam。
 *
 * <p>结算代码只依赖该接口产生的结构化事实，后续可按 provider/model 替换更精确的
 * tokenizer，而无需修改监听与结算流程。</p>
 */
public interface ModelTokenUsageEstimator {

    EstimatedModelTokenUsage estimate(ChatRequest request, ChatResponse response);
}
