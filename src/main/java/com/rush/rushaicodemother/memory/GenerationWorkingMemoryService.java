package com.rush.rushaicodemother.memory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.config.GenerationWorkingMemoryProperties;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;

/** 活动代理图的每任务短期记忆有界。 */
@Service
public class GenerationWorkingMemoryService {
    private final Cache<String, MutableWorkingMemory> memories;
    private final int maxRecentEvents;

    public GenerationWorkingMemoryService(GenerationWorkingMemoryProperties properties) {
        this.maxRecentEvents = properties.getMaxRecentEvents();
        this.memories = Caffeine.newBuilder()
                .maximumSize(properties.getMaxTasks())
                .expireAfterAccess(properties.getRetention())
                .build();
    }

    /**
 * 初始化生成{@code Working}记忆。
 *
 * @param taskId 任务编号
 * @param appId 应用编号
 * @param userId 用户编号
 * @param route 代理路由
 */
    public void initialize(String taskId, Long appId, Long userId, String route) {
        if (!validTaskId(taskId)) {
            return;
        }
        memories.asMap().compute(taskId, (ignored, existing) -> {
            if (existing == null) {
                return new MutableWorkingMemory(taskId, appId, userId, route);
            }
            existing.updateRoute(route);
            return existing;
        });
    }

    /**
 * 记录事件相关指标或状态。
 *
 * @param taskId 任务编号
 * @param event 待处理的领域事件
 */
    public void recordEvent(String taskId, GenerationStreamEvent event) {
        MutableWorkingMemory memory = memories.getIfPresent(taskId);
        if (memory != null && event != null) {
            memory.record(event, maxRecentEvents);
        }
    }

    /**
 * 记录上下文摘要相关指标或状态。
 *
 * @param taskId 任务编号
 * @param contextDigest 上下文摘要
 */
    public void recordContextDigest(String taskId, String contextDigest) {
        MutableWorkingMemory memory = memories.getIfPresent(taskId);
        if (memory != null) {
            memory.recordContextDigest(contextDigest);
        }
    }

    /**
 * 完成生成{@code Working}记忆并持久化终态。
 *
 * @param taskId 任务编号
 */
    public void complete(String taskId) {
        MutableWorkingMemory memory = memories.getIfPresent(taskId);
        if (memory != null) {
            memory.complete();
        }
    }

    /**
 * 返回快照。
 *
 * @param taskId 任务编号
 * @return 可选的生成{@code Working}记忆；不存在时返回空值
 */
    public Optional<GenerationWorkingMemorySnapshot> snapshot(String taskId) {
        MutableWorkingMemory memory = memories.getIfPresent(taskId);
        return memory == null ? Optional.empty() : Optional.of(memory.snapshot());
    }

    private boolean validTaskId(String taskId) {
        return taskId != null && taskId.matches("[A-Za-z0-9_-]{1,128}");
    }

    private static final class MutableWorkingMemory {
        private final String taskId;
        private final Long appId;
        private final Long userId;
        private String route;
        private final Deque<GenerationStreamEvent> recentEvents = new ArrayDeque<>();
        private String currentStage = "queued";
        private String currentSummary = "";
        private String contextDigest = "";
        private boolean completed;
        private Instant updatedAt = Instant.now();

        private MutableWorkingMemory(String taskId, Long appId, Long userId, String route) {
            this.taskId = taskId;
            this.appId = appId;
            this.userId = userId;
            this.route = route;
        }

        /** 记录{@code Mutable}{@code Working}记忆相关指标或状态。 */
        private synchronized void record(GenerationStreamEvent event, int limit) {
            recentEvents.addLast(event);
            while (recentEvents.size() > limit) {
                recentEvents.removeFirst();
            }
            Map<String, Object> data = event.getData();
            if (data != null) {
                currentStage = stringValue(data.get("stage"), currentStage);
                currentSummary = stringValue(data.get("summary"), currentSummary);
                contextDigest = stringValue(data.get("contextDigest"), contextDigest);
            }
            updatedAt = Instant.now();
        }

        private synchronized void updateRoute(String route) {
            this.route = route;
            updatedAt = Instant.now();
        }

        private synchronized void recordContextDigest(String digest) {
            contextDigest = digest == null ? "" : digest;
            updatedAt = Instant.now();
        }

        private synchronized void complete() {
            completed = true;
            updatedAt = Instant.now();
        }

        private synchronized GenerationWorkingMemorySnapshot snapshot() {
            return new GenerationWorkingMemorySnapshot(
                    taskId, appId, userId, route, currentStage, currentSummary,
                    contextDigest, completed, updatedAt, java.util.List.copyOf(recentEvents));
        }

        private String stringValue(Object value, String fallback) {
            return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
        }
    }
}
