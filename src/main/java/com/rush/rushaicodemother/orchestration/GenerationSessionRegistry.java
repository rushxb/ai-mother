package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 单实例生成会话注册表。
 *
 * <p>同一应用的启动互斥使用固定数量的条带锁，避免按应用 ID 永久累积锁对象。
 * 已完成会话只记录过期时间，由一个固定周期任务批量清理，不为每个会话创建延迟任务。</p>
 */
@Component
public class GenerationSessionRegistry {

    private final Object sessionMutationMonitor = new Object();
    private final Object[] lockStripes;
    private final Map<Long, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final int maxTrackedSessions;
    private final long completedReplayRetentionNanos;
    private final LongSupplier nanoTimeSupplier;

    @Autowired
    public GenerationSessionRegistry(GenerationSessionProperties properties) {
        this(properties, System::nanoTime);
    }

    GenerationSessionRegistry(GenerationSessionProperties properties, LongSupplier nanoTimeSupplier) {
        if (properties == null) {
            throw new IllegalArgumentException("properties cannot be null");
        }
        if (nanoTimeSupplier == null) {
            throw new IllegalArgumentException("nanoTimeSupplier cannot be null");
        }
        this.lockStripes = createLockStripes(properties.getLockStripes());
        this.maxTrackedSessions = requirePositive(properties.getMaxTrackedSessions(), "maxTrackedSessions");
        this.completedReplayRetentionNanos = requirePositiveDuration(
                properties.getCompletedReplayRetention(), "completedReplayRetention").toNanos();
        this.nanoTimeSupplier = nanoTimeSupplier;
    }

    public GenerationSession get(Long appId) {
        long validatedAppId = requirePositiveAppId(appId);
        while (true) {
            SessionEntry entry = sessions.get(validatedAppId);
            if (entry == null) {
                return null;
            }
            if (!entry.isExpired(nanoTimeSupplier.getAsLong())) {
                return entry.session();
            }
            if (removeIfCurrent(validatedAppId, entry)) {
                return null;
            }
        }
    }

    public void put(Long appId, GenerationSession session) {
        long validatedAppId = requirePositiveAppId(appId);
        GenerationSession validatedSession = requireSession(session);
        synchronized (sessionMutationMonitor) {
            if (!sessions.containsKey(validatedAppId) && sessions.size() >= maxTrackedSessions) {
                removeExpiredSessions(nanoTimeSupplier.getAsLong());
            }
            if (!sessions.containsKey(validatedAppId) && sessions.size() >= maxTrackedSessions) {
                throw new GenerationSessionCapacityExceededException(maxTrackedSessions);
            }
            sessions.put(validatedAppId, SessionEntry.active(validatedSession));
        }
    }

    public void remove(Long appId, GenerationSession session) {
        long validatedAppId = requirePositiveAppId(appId);
        GenerationSession validatedSession = requireSession(session);
        synchronized (sessionMutationMonitor) {
            SessionEntry current = sessions.get(validatedAppId);
            if (current != null && current.session() == validatedSession) {
                sessions.remove(validatedAppId, current);
            }
        }
    }

    public void remove(Long appId) {
        long validatedAppId = requirePositiveAppId(appId);
        synchronized (sessionMutationMonitor) {
            sessions.remove(validatedAppId);
        }
    }

    public Object lock(Long appId) {
        long validatedAppId = requirePositiveAppId(appId);
        int stripeIndex = Math.floorMod(Long.hashCode(validatedAppId), lockStripes.length);
        return lockStripes[stripeIndex];
    }

    public void assertNoActiveSession(Long appId) {
        GenerationSession session = get(appId);
        if (session != null && session.isActive()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "当前应用正在生成中，请稍后再试");
        }
    }

    /**
     * 将当前已完成会话标记为短期可回放。若该应用已绑定新会话，旧会话不会影响新会话。
     */
    public void retainForReplay(Long appId, GenerationSession session) {
        long validatedAppId = requirePositiveAppId(appId);
        GenerationSession validatedSession = requireSession(session);
        long expirationNanos = nanoTimeSupplier.getAsLong() + completedReplayRetentionNanos;
        synchronized (sessionMutationMonitor) {
            SessionEntry current = sessions.get(validatedAppId);
            if (current != null && current.session() == validatedSession) {
                sessions.put(validatedAppId, current.expiringAt(expirationNanos));
            }
        }
    }

    int cleanupExpiredSessions() {
        synchronized (sessionMutationMonitor) {
            return removeExpiredSessions(nanoTimeSupplier.getAsLong());
        }
    }

    int trackedSessionCount() {
        return sessions.size();
    }

    private int removeExpiredSessions(long nowNanos) {
        int removed = 0;
        for (Map.Entry<Long, SessionEntry> candidate : sessions.entrySet()) {
            SessionEntry entry = candidate.getValue();
            if (entry.isExpired(nowNanos) && sessions.remove(candidate.getKey(), entry)) {
                removed++;
            }
        }
        return removed;
    }

    private boolean removeIfCurrent(long appId, SessionEntry expectedEntry) {
        synchronized (sessionMutationMonitor) {
            SessionEntry current = sessions.get(appId);
            if (current != expectedEntry || !current.isExpired(nanoTimeSupplier.getAsLong())) {
                return false;
            }
            return sessions.remove(appId, current);
        }
    }

    private Object[] createLockStripes(int stripeCount) {
        int validatedStripeCount = requirePositive(stripeCount, "lockStripes");
        Object[] stripes = new Object[validatedStripeCount];
        for (int index = 0; index < stripes.length; index++) {
            stripes[index] = new Object();
        }
        return stripes;
    }

    private int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private Duration requirePositiveDuration(Duration duration, String fieldName) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return duration;
    }

    private long requirePositiveAppId(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 必须为正整数");
        }
        return appId;
    }

    private GenerationSession requireSession(GenerationSession session) {
        if (session == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成会话不能为空");
        }
        return session;
    }

    private record SessionEntry(GenerationSession session, Long expirationNanos) {

        private static SessionEntry active(GenerationSession session) {
            return new SessionEntry(session, null);
        }

        private SessionEntry expiringAt(long expirationNanos) {
            return new SessionEntry(session, expirationNanos);
        }

        private boolean isExpired(long nowNanos) {
            return expirationNanos != null && nowNanos - expirationNanos >= 0;
        }
    }
}