package com.rush.rushaicodemother.service.devserver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dev Server 错误收集器
 * <p>
 * 线程安全，用于从 dev server 输出流中实时收集错误。
 * 同类错误会合并出现次数，避免刷屏。
 */
public class DevServerErrorCollector {

    /**
     * 最大保留原始行数（防止内存溢出）
     */
    private static final int MAX_RAW_LINES = 500;

    /**
     * 原始输出行缓冲（用于调试）
     */
    private final ConcurrentLinkedQueue<String> rawLines = new ConcurrentLinkedQueue<>();

    /**
     * 已检测到的错误（按 pattern code 去重，保留第一次出现的完整错误 + 累加计数）
     */
    private final Map<String, DevServerError> detectedErrors = new LinkedHashMap<>();

    /**
     * 是否检测到 Critical 级别错误
     */
    private final AtomicBoolean hasCriticalError = new AtomicBoolean(false);

    /**
     * 是否检测到 Warning 级别错误
     */
    private final AtomicBoolean hasWarning = new AtomicBoolean(false);

    /**
     * 行计数
     */
    private int lineCount = 0;

    /**
     * 向收集器输入一行 dev server 输出
     * <p>
     * 此方法线程安全，可从输出流读取线程直接调用。
     *
     * @param line 原始输出行
     */
    public synchronized void feedLine(String line) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (line == null) {
            return;
        }

        // 保留原始行（带上限）
        rawLines.add(line.trim());
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        while (rawLines.size() > MAX_RAW_LINES) {
            rawLines.poll();
        }
        lineCount++;

        // 尝试匹配错误模式
        DevServerError error = DevServerError.tryMatch(line);
        if (error == null) {
            return;
        }

        String key = error.pattern().getCode();
        DevServerError existing = detectedErrors.get(key);
        if (existing != null) {
            // 同类错误累加计数
            detectedErrors.put(key, existing.withIncrementedCount());
        } else {
            detectedErrors.put(key, error);
        }

        if (error.pattern().isCritical()) {
            hasCriticalError.set(true);
        } else {
            hasWarning.set(true);
        }
    }

    /**
     * 获取所有检测到的错误列表
     */
    public synchronized List<DevServerError> getErrors() {
        return new ArrayList<>(detectedErrors.values());
    }

    /**
     * 是否有 Critical 级别错误
     */
    public boolean hasCriticalError() {
        return hasCriticalError.get();
    }

    /**
     * 是否有 Warning 级别错误
     */
    public boolean hasWarning() {
        return hasWarning.get();
    }

    /**
     * 是否有任何错误（Critical 或 Warning）
     */
    public boolean hasAnyError() {
        return hasCriticalError.get() || hasWarning.get();
    }

    /**
     * 获取原始输出行（用于调试/日志）
     */
    public synchronized List<String> getRawLines() {
        return new ArrayList<>(rawLines);
    }

    /**
     * 获取总行数
     */
    public synchronized int getLineCount() {
        return lineCount;
    }

    /**
     * 获取 Critical 错误数量
     */
    public synchronized int getCriticalErrorCount() {
        return (int) detectedErrors.values().stream()
                .filter(e -> e.pattern().isCritical())
                .count();
    }

    /**
     * 获取 Warning 数量
     */
    public synchronized int getWarningCount() {
        return (int) detectedErrors.values().stream()
                .filter(e -> !e.pattern().isCritical())
                .count();
    }

    /**
     * 清空收集器
     */
    public synchronized void clear() {
        rawLines.clear();
        detectedErrors.clear();
        hasCriticalError.set(false);
        hasWarning.set(false);
        lineCount = 0;
    }
}
