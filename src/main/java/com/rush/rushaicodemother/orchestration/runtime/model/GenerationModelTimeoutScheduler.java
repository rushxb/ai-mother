package com.rush.rushaicodemother.orchestration.runtime.model;

import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;

import java.time.Duration;

/** 调度可取消的模型超时任务。 */
public interface GenerationModelTimeoutScheduler {

    GenerationCancellationHandle schedule(Duration delay, Runnable timeoutAction);
}
