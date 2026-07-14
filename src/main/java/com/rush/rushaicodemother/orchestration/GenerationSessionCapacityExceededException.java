package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ServiceUnavailableException;

/**
 * 单实例生成会话注册表已达到配置容量。
 */
public final class GenerationSessionCapacityExceededException extends ServiceUnavailableException {

    public GenerationSessionCapacityExceededException(int maximumSessions) {
        super(
                ErrorCode.SERVICE_UNAVAILABLE_ERROR.getMessage(),
                "Generation session registry reached the configured capacity of "
                        + maximumSessions
                        + "; wait for active tasks to finish or increase app.generation-session.max-tracked-sessions"
        );
    }
}