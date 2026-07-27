package com.rush.rushaicodemother.ai.model.capacity;

import dev.langchain4j.model.chat.request.ChatRequest;

import java.time.Duration;

/** 在真正的上游调用之前获取一个集群范围的提供者/模型容量租赁。 */
public interface AiModelCapacityGuard {

    Lease acquire(String provider,
                  String modelId,
                  int configuredMaxOutputTokens,
                  ChatRequest request);

    /**
     * 获取具有已知挂钟超时的上游调用的容量。
     *
     * <p> 默认保持与不管理可更新租约的守卫的兼容性。的
     * 分布式实现使用超时来限制小范围宽限后的心跳更新
     * 期间，因此损坏的提供商客户端无法永远保持集群容量。</p>
     */
    default Lease acquire(String provider,
                          String modelId,
                          int configuredMaxOutputTokens,
                          ChatRequest request,
                          Duration upstreamTimeout) {
        return acquire(provider, modelId, configuredMaxOutputTokens, request);
    }

    @FunctionalInterface
    interface Lease extends AutoCloseable {
        Lease NOOP = () -> { };

        /** 当分发的许可证不再被安全拥有后返回 false。 */
        default boolean isValid() {
            return true;
        }

        /** 注册一个用于取消正在进行的流请求的尽力回调。 */
        default void onLost(Runnable listener) {
            if (listener == null) {
                throw new IllegalArgumentException("capacity lease loss listener is required");
            }
        }

        @Override
        void close();
    }
}
