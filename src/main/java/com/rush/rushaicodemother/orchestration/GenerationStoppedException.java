package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.core.error.GenerationCancellationSignal;

/**
 * 生成Stopped业务异常。
 */
public final class GenerationStoppedException extends RuntimeException implements GenerationCancellationSignal {
}
