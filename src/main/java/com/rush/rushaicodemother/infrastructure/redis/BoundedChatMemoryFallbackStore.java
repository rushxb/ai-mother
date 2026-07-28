package com.rush.rushaicodemother.infrastructure.redis;

import dev.langchain4j.data.message.ChatMessage;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 对话记忆故障状态的有界进程内存储。
 *
 * <p>已同步副本允许按访问时间过期并在容量不足时优先淘汰；尚未回灌 Redis 的更新和删除
 * 不会过期、也不会被静默淘汰。若全部容量均被待回灌变更占用，新变更会被明确拒绝，
 * 从而避免向调用方返回成功后再静默丢失数据。</p>
 */
final class BoundedChatMemoryFallbackStore {

    private final long maximumEntries;
    private final long expireAfterAccessNanos;
    private final LongSupplier nanoTime;
    private final Map<Object, StoredState> states = new LinkedHashMap<>(16, 0.75F, true);
    private final ReentrantLock lock = new ReentrantLock();

    BoundedChatMemoryFallbackStore(long maximumEntries, Duration expireAfterAccess) {
        this(maximumEntries, expireAfterAccess, System::nanoTime);
    }

    /** 创建{@code Bounded}对话记忆回退存储实例并完成必要的依赖和初始状态设置。 */
    BoundedChatMemoryFallbackStore(
            long maximumEntries,
            Duration expireAfterAccess,
            LongSupplier nanoTime
    ) {
        if (maximumEntries <= 0) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        if (expireAfterAccess == null || expireAfterAccess.isZero() || expireAfterAccess.isNegative()) {
            throw new IllegalArgumentException("expireAfterAccess must be positive");
        }
        this.maximumEntries = maximumEntries;
        this.expireAfterAccessNanos = toNanosSaturated(expireAfterAccess);
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /** 获取并返回{@code If}{@code Present}。 */
    FallbackState getIfPresent(Object memoryId) {
        lock.lock();
        try {
            StoredState storedState = states.get(memoryId);
            if (storedState == null) {
                return null;
            }
            long now = nanoTime.getAsLong();
            if (storedState.isSynchronizedCopy() && isExpired(storedState, now)) {
                states.remove(memoryId);
                return null;
            }
            storedState.lastAccessNanos = now;
            return storedState.state;
        } finally {
            lock.unlock();
        }
    }

    void putSynchronizedMessages(Object memoryId, List<ChatMessage> messages) {
        put(memoryId, FallbackState.synchronizedMessages(messages), false);
    }

    void putPendingUpdate(Object memoryId, List<ChatMessage> messages) {
        put(memoryId, FallbackState.pendingUpdate(messages), true);
    }

    void putPendingDelete(Object memoryId) {
        put(memoryId, FallbackState.pendingDelete(), true);
    }

    void invalidate(Object memoryId) {
        lock.lock();
        try {
            states.remove(memoryId);
        } finally {
            lock.unlock();
        }
    }

    /** 处理{@code put}。 */
    private void put(Object memoryId, FallbackState state, boolean pendingMutation) {
        Objects.requireNonNull(memoryId, "memoryId");
        Objects.requireNonNull(state, "state");
        lock.lock();
        try {
            long now = nanoTime.getAsLong();
            removeExpiredSynchronizedCopies(now);
            if (!states.containsKey(memoryId) && states.size() >= maximumEntries) {
                boolean evicted = evictOldestSynchronizedCopy();
                if (!evicted) {
                    if (pendingMutation) {
                        throw new ChatMemoryFallbackCapacityExceededException(maximumEntries);
                    }
                    return;
                }
            }
            states.put(memoryId, new StoredState(state, now));
        } finally {
            lock.unlock();
        }
    }

    /** 移除{@code Expired}{@code Synchronized}{@code Copies}。 */
    private void removeExpiredSynchronizedCopies(long now) {
        Iterator<Map.Entry<Object, StoredState>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            StoredState storedState = iterator.next().getValue();
            if (storedState.isSynchronizedCopy() && isExpired(storedState, now)) {
                iterator.remove();
            }
        }
    }

    /** 返回{@code evict}{@code Oldest}{@code Synchronized}文案。 */
    private boolean evictOldestSynchronizedCopy() {
        Iterator<Map.Entry<Object, StoredState>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().isSynchronizedCopy()) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private boolean isExpired(StoredState storedState, long now) {
        return now - storedState.lastAccessNanos >= expireAfterAccessNanos;
    }

    /** 将目标时长转换为防溢出的纳秒数。 */
    private static long toNanosSaturated(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    enum MutationType {
        NONE,
        UPDATE,
        DELETE
    }

    record FallbackState(List<ChatMessage> messages, MutationType pendingMutation) {

        FallbackState {
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            Objects.requireNonNull(pendingMutation, "pendingMutation");
        }

        static FallbackState synchronizedMessages(List<ChatMessage> messages) {
            return new FallbackState(messages, MutationType.NONE);
        }

        static FallbackState pendingUpdate(List<ChatMessage> messages) {
            return new FallbackState(messages, MutationType.UPDATE);
        }

        static FallbackState pendingDelete() {
            return new FallbackState(List.of(), MutationType.DELETE);
        }
    }

    private static final class StoredState {

        private final FallbackState state;
        private long lastAccessNanos;

        private StoredState(FallbackState state, long lastAccessNanos) {
            this.state = state;
            this.lastAccessNanos = lastAccessNanos;
        }

        private boolean isSynchronizedCopy() {
            return state.pendingMutation() == MutationType.NONE;
        }
    }
}
