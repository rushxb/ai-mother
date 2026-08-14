package com.rush.rushaicodemother.orchestration.event;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 生成事件的有界重放窗口。
 *
 * <p>该类只管理重放状态，不感知 Reactor 或业务发布流程。驱逐回调由上层用于同步
 * 释放实时订阅资源，保证重放窗口和实时流具有一致的生命周期。</p>
 *
 * <p>线程安全由 {@link GenerationEventPublisher} 的状态锁保障，避免两层锁交叉。</p>
 */
final class GenerationEventReplayBuffer {

    private final Clock clock;
    private final Duration retention;
    private final int maxTrackedApps;
    private final int maxEventsPerApp;
    private final Consumer<Long> evictionListener;
    private final LinkedHashMap<Long, ReplayEntry> entries = new LinkedHashMap<>(16, 0.75F, true);

    GenerationEventReplayBuffer(Clock clock,
                                Duration retention,
                                int maxTrackedApps,
                                int maxEventsPerApp,
                                Consumer<Long> evictionListener) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = requirePositive(retention, "retention");
        if (maxTrackedApps <= 0) {
            throw new IllegalArgumentException("maxTrackedApps must be positive");
        }
        if (maxEventsPerApp <= 0) {
            throw new IllegalArgumentException("maxEventsPerApp must be positive");
        }
        this.maxTrackedApps = maxTrackedApps;
        this.maxEventsPerApp = maxEventsPerApp;
        this.evictionListener = Objects.requireNonNull(evictionListener, "evictionListener");
    }

    boolean append(GenerationEvent event, String idempotencyKey) {
        Objects.requireNonNull(event, "event");
        Long appId = Objects.requireNonNull(event.appId(), "event.appId");
        Instant now = clock.instant();
        evictExpired(now);
        ReplayEntry entry = entries.computeIfAbsent(appId, ignored -> new ReplayEntry(now));
        entry.lastAccessedAt = now;
        if (idempotencyKey != null && containsEventId(entry.events, idempotencyKey)) {
            return false;
        }
        entry.events.addLast(event);
        while (entry.events.size() > maxEventsPerApp) {
            entry.events.removeFirst();
        }
        evictOverflow();
        return true;
    }

    List<GenerationEvent> snapshot(Long appId) {
        if (appId == null) {
            return List.of();
        }
        Instant now = clock.instant();
        evictExpired(now);
        ReplayEntry entry = entries.get(appId);
        if (entry == null) {
            return List.of();
        }
        entry.lastAccessedAt = now;
        return List.copyOf(entry.events);
    }

    List<GenerationEvent> open(Long appId) {
        Objects.requireNonNull(appId, "appId");
        Instant now = clock.instant();
        evictExpired(now);
        ReplayEntry entry = entries.computeIfAbsent(appId, ignored -> new ReplayEntry(now));
        entry.lastAccessedAt = now;
        evictOverflow();
        return List.copyOf(entry.events);
    }

    void remove(Long appId) {
        if (appId != null && entries.remove(appId) != null) {
            evictionListener.accept(appId);
        }
    }

    private void evictExpired(Instant now) {
        List<Long> evictedAppIds = new ArrayList<>();
        Iterator<Map.Entry<Long, ReplayEntry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, ReplayEntry> candidate = iterator.next();
            if (!isExpired(candidate.getValue(), now)) {
                continue;
            }
            Long evictedAppId = candidate.getKey();
            iterator.remove();
            evictedAppIds.add(evictedAppId);
        }
        evictedAppIds.forEach(evictionListener);
    }

    private void evictOverflow() {
        List<Long> evictedAppIds = new ArrayList<>();
        while (entries.size() > maxTrackedApps) {
            Iterator<Map.Entry<Long, ReplayEntry>> iterator = entries.entrySet().iterator();
            Map.Entry<Long, ReplayEntry> leastRecentlyUsed = iterator.next();
            Long evictedAppId = leastRecentlyUsed.getKey();
            iterator.remove();
            evictedAppIds.add(evictedAppId);
        }
        evictedAppIds.forEach(evictionListener);
    }

    private boolean isExpired(ReplayEntry entry, Instant now) {
        if (now.isBefore(entry.lastAccessedAt)) {
            return false;
        }
        return Duration.between(entry.lastAccessedAt, now).compareTo(retention) >= 0;
    }

    private boolean containsEventId(Deque<GenerationEvent> events, String eventId) {
        return events.stream().anyMatch(existing -> existing.data() != null
                && eventId.equals(String.valueOf(existing.data().get("eventId"))));
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static final class ReplayEntry {

        private final Deque<GenerationEvent> events = new ArrayDeque<>();
        private Instant lastAccessedAt;

        private ReplayEntry(Instant lastAccessedAt) {
            this.lastAccessedAt = lastAccessedAt;
        }
    }
}
