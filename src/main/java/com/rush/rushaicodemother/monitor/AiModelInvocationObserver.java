package com.rush.rushaicodemother.monitor;

/** 观察已经发往物理模型提供方的请求，不参与请求内容处理。 */
public interface AiModelInvocationObserver {

    void onRequest(String provider, String modelId);
}
