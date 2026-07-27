package com.rush.rushaicodemother.ai.model.failover;

import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;

import java.time.Duration;

/** 为首 Token 对冲提供可取消的延迟调度能力。 */
@FunctionalInterface
public interface FirstTokenHedgeScheduler {

    GenerationCancellationHandle schedule(Duration delay, Runnable task);
}
