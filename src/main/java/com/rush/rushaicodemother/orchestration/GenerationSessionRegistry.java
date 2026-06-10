package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class GenerationSessionRegistry {

    private final Map<Long, Object> locks = new ConcurrentHashMap<>();
    private final Map<Long, GenerationSession> sessions = new ConcurrentHashMap<>();

    public GenerationSession get(Long appId) {
        return sessions.get(appId);
    }

    public void put(Long appId, GenerationSession session) {
        if (appId != null && session != null) {
            sessions.put(appId, session);
        }
    }

    public void remove(Long appId, GenerationSession session) {
        if (appId != null && session != null) {
            sessions.remove(appId, session);
        }
    }

    public void remove(Long appId) {
        if (appId != null) {
            sessions.remove(appId);
        }
    }

    public Object lock(Long appId) {
        if (appId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        }
        return locks.computeIfAbsent(appId, key -> new Object());
    }

    public void assertNoActiveSession(Long appId) {
        if (appId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        }
        GenerationSession session = sessions.get(appId);
        if (session != null && session.isActive()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "当前应用正在生成中，请稍后再试");
        }
    }

    public void cleanupLater(Long appId, GenerationSession session, long delaySeconds) {
        CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS).execute(() -> remove(appId, session));
    }
}
