package com.rush.rushaicodemother.infrastructure.redis;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.dao.DataAccessException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Redis 对话记忆的运行时故障转移边界。
 *
 * <p>Redis 访问故障时使用有界、可过期的进程内同步副本继续服务，并保存尚未同步的更新或删除操作；
 * 待同步变更不会过期或被静默淘汰。Redis 恢复后，下一次访问会先回灌待同步变更，
 * 避免恢复瞬间用旧数据覆盖故障期间的新对话。</p>
 */
public class FailoverChatMemoryStore implements ChatMemoryStore {

    private static final int LOCK_STRIPE_COUNT = 64;
    private static final int MAX_MEMORY_ID_LENGTH = 128;

    private final ChatMemoryStore primaryStore;
    private final OptionalRedisOperationMonitor monitor;
    private final BoundedChatMemoryFallbackStore fallbackStore;
    private final ReentrantLock[] locks = createLocks();

    public FailoverChatMemoryStore(
            ChatMemoryStore primaryStore,
            OptionalRedisOperationMonitor monitor,
            long fallbackMaxEntries,
            Duration fallbackExpireAfterAccess
    ) {
        this.primaryStore = Objects.requireNonNull(primaryStore, "primaryStore");
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.fallbackStore = new BoundedChatMemoryFallbackStore(
                fallbackMaxEntries,
                fallbackExpireAfterAccess
        );
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        Object requiredMemoryId = requireMemoryId(memoryId);
        ReentrantLock lock = lockFor(requiredMemoryId);
        lock.lock();
        try {
            BoundedChatMemoryFallbackStore.FallbackState fallbackState =
                    fallbackStore.getIfPresent(requiredMemoryId);
            if (fallbackState != null
                    && fallbackState.pendingMutation() != BoundedChatMemoryFallbackStore.MutationType.NONE) {
                return flushPendingMutation(requiredMemoryId, fallbackState);
            }
            try {
                List<ChatMessage> messages = immutableMessages(primaryStore.getMessages(requiredMemoryId));
                fallbackStore.putSynchronizedMessages(requiredMemoryId, messages);
                monitor.recordSuccess(OptionalRedisOperation.CHAT_MEMORY_GET);
                return messages;
            } catch (DataAccessException exception) {
                monitor.recordFailure(OptionalRedisOperation.CHAT_MEMORY_GET, exception);
                return fallbackState == null ? List.of() : fallbackState.messages();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        Object requiredMemoryId = requireMemoryId(memoryId);
        List<ChatMessage> requiredMessages = requireNonEmptyMessages(messages);
        ReentrantLock lock = lockFor(requiredMemoryId);
        lock.lock();
        try {
            try {
                primaryStore.updateMessages(requiredMemoryId, requiredMessages);
                fallbackStore.putSynchronizedMessages(requiredMemoryId, requiredMessages);
                monitor.recordSuccess(OptionalRedisOperation.CHAT_MEMORY_UPDATE);
            } catch (DataAccessException exception) {
                monitor.recordFailure(OptionalRedisOperation.CHAT_MEMORY_UPDATE, exception);
                fallbackStore.putPendingUpdate(requiredMemoryId, requiredMessages);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        Object requiredMemoryId = requireMemoryId(memoryId);
        ReentrantLock lock = lockFor(requiredMemoryId);
        lock.lock();
        try {
            try {
                primaryStore.deleteMessages(requiredMemoryId);
                fallbackStore.invalidate(requiredMemoryId);
                monitor.recordSuccess(OptionalRedisOperation.CHAT_MEMORY_DELETE);
            } catch (DataAccessException exception) {
                monitor.recordFailure(OptionalRedisOperation.CHAT_MEMORY_DELETE, exception);
                fallbackStore.putPendingDelete(requiredMemoryId);
            }
        } finally {
            lock.unlock();
        }
    }

    private List<ChatMessage> flushPendingMutation(
            Object memoryId,
            BoundedChatMemoryFallbackStore.FallbackState fallbackState
    ) {
        OptionalRedisOperation operation = fallbackState.pendingMutation()
                == BoundedChatMemoryFallbackStore.MutationType.UPDATE
                ? OptionalRedisOperation.CHAT_MEMORY_UPDATE
                : OptionalRedisOperation.CHAT_MEMORY_DELETE;
        try {
            if (fallbackState.pendingMutation() == BoundedChatMemoryFallbackStore.MutationType.UPDATE) {
                primaryStore.updateMessages(memoryId, fallbackState.messages());
                fallbackStore.putSynchronizedMessages(memoryId, fallbackState.messages());
            } else {
                primaryStore.deleteMessages(memoryId);
                fallbackStore.invalidate(memoryId);
            }
            monitor.recordSuccess(operation);
        } catch (DataAccessException exception) {
            monitor.recordFailure(operation, exception);
        }
        return fallbackState.messages();
    }

    private Object requireMemoryId(Object memoryId) {
        if (memoryId == null) {
            throw new IllegalArgumentException("memoryId cannot be null or blank");
        }
        String normalizedMemoryId = memoryId.toString().trim();
        if (normalizedMemoryId.isEmpty() || normalizedMemoryId.length() > MAX_MEMORY_ID_LENGTH) {
            throw new IllegalArgumentException("memoryId must contain 1 to 128 characters");
        }
        return memoryId;
    }

    private List<ChatMessage> requireNonEmptyMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages cannot be null or empty");
        }
        return immutableMessages(messages);
    }

    private List<ChatMessage> immutableMessages(List<ChatMessage> messages) {
        return List.copyOf(Objects.requireNonNull(messages, "messages"));
    }

    private ReentrantLock lockFor(Object memoryId) {
        int hash = memoryId.hashCode();
        int spreadHash = hash ^ (hash >>> 16);
        return locks[spreadHash & (LOCK_STRIPE_COUNT - 1)];
    }

    private ReentrantLock[] createLocks() {
        ReentrantLock[] createdLocks = new ReentrantLock[LOCK_STRIPE_COUNT];
        for (int index = 0; index < createdLocks.length; index++) {
            createdLocks[index] = new ReentrantLock();
        }
        return createdLocks;
    }

}
