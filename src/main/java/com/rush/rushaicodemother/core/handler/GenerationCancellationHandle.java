package com.rush.rushaicodemother.core.handler;

/**
 * 项目拥有的合同，用于取消主动人工智能生成请求。
 */
@FunctionalInterface
public interface GenerationCancellationHandle {

    void cancel();
}
