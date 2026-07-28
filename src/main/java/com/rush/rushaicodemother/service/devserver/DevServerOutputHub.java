package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

/**
 * Dev Server 输出的单一分发入口，统一负责错误收集和最近输出的内存上限。
 */
@Component
public class DevServerOutputHub {

    private static final String TRUNCATION_MARKER = " …[输出已截断]";

    private final int maxRecentOutputLines;
    private final int maxOutputLineLength;
    private final Map<Long, Set<DevServerErrorCollector>> errorCollectors = new ConcurrentHashMap<>();
    private final Map<Long, ConcurrentLinkedDeque<String>> recentOutputLines = new ConcurrentHashMap<>();

    @Autowired
    public DevServerOutputHub(DevServerRuntimeProperties properties) {
        this(properties.getMaxRecentOutputLines(), properties.getMaxOutputLineLength());
    }

    DevServerOutputHub(int maxRecentOutputLines, int maxOutputLineLength) {
        if (maxRecentOutputLines <= 0 || maxOutputLineLength <= 0) {
            throw new IllegalArgumentException("Dev Server 输出限制必须大于 0");
        }
        this.maxRecentOutputLines = maxRecentOutputLines;
        this.maxOutputLineLength = maxOutputLineLength;
    }

    /**
 * 准备后续流程所需的开发服务器输出{@code Hub}。
 *
 * @param appId 应用编号
 */
    public void prepare(Long appId) {
        if (appId != null) {
            recentOutputLines.remove(appId);
        }
    }

    public Consumer<String> sink(Long appId) {
        return line -> accept(appId, line);
    }

    /**
 * 注册采集器。
 *
 * @param appId 应用编号
 * @param collector 采集器
 */
    public void registerCollector(Long appId, DevServerErrorCollector collector) {
        if (appId == null || appId <= 0 || collector == null) {
            throw new IllegalArgumentException("应用 ID 和错误收集器不能为空");
        }
        errorCollectors.computeIfAbsent(appId, ignored -> ConcurrentHashMap.newKeySet())
                .add(collector);
    }

    /**
 * 注销采集器。
 *
 * @param appId 应用编号
 * @param collector 采集器
 */
    public void unregisterCollector(Long appId, DevServerErrorCollector collector) {
        if (appId == null || collector == null) {
            return;
        }
        Set<DevServerErrorCollector> collectors = errorCollectors.get(appId);
        if (collectors == null) {
            return;
        }
        collectors.remove(collector);
        if (collectors.isEmpty()) {
            errorCollectors.remove(appId, collectors);
        }
    }

    /**
 * 返回{@code recent}{@code Lines}。
 *
 * @param appId 应用编号
 * @param limit 资源上限
 * @return 开发服务器输出{@code Hub}集合
 */
    public List<String> recentLines(Long appId, int limit) {
        if (appId == null || limit <= 0) {
            return List.of();
        }
        ConcurrentLinkedDeque<String> lines = recentOutputLines.get(appId);
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<String> snapshot = new ArrayList<>(lines);
        int effectiveLimit = Math.min(limit, maxRecentOutputLines);
        int fromIndex = Math.max(0, snapshot.size() - effectiveLimit);
        return List.copyOf(snapshot.subList(fromIndex, snapshot.size()));
    }

    /** 清理开发服务器输出{@code Hub}。 */
    public void clear() {
        errorCollectors.clear();
        recentOutputLines.clear();
    }

    /** 接收并处理开发服务器输出{@code Hub}。 */
    private void accept(Long appId, String rawLine) {
        if (appId == null || rawLine == null || rawLine.isBlank()) {
            return;
        }
        String line = boundLine(rawLine.strip());
        ConcurrentLinkedDeque<String> lines = recentOutputLines.computeIfAbsent(
                appId,
                ignored -> new ConcurrentLinkedDeque<>()
        );
        lines.addLast(line);
        while (lines.size() > maxRecentOutputLines) {
            lines.pollFirst();
        }

        Set<DevServerErrorCollector> collectors = errorCollectors.get(appId);
        if (collectors != null) {
            collectors.forEach(collector -> collector.feedLine(line));
        }
    }

    /** 返回绑定{@code Line}。 */
    private String boundLine(String line) {
        if (line.length() <= maxOutputLineLength) {
            return line;
        }
        if (maxOutputLineLength <= TRUNCATION_MARKER.length()) {
            return line.substring(0, maxOutputLineLength);
        }
        return line.substring(0, maxOutputLineLength - TRUNCATION_MARKER.length()) + TRUNCATION_MARKER;
    }
}
