package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单实例内的 Dev Server 端口保留器。
 * 所有检查和写入均在同一把锁内完成，避免并发应用获得同一端口。
 */
@Slf4j
@Component
public class DevServerPortAllocator {

    private static final String LOOPBACK_ADDRESS = "127.0.0.1";

    private final int rangeStart;
    private final int rangeEnd;
    private final ReentrantLock allocationLock = new ReentrantLock();
    private final Map<Long, Integer> appPorts = new HashMap<>();
    private int nextCandidate;

    @Autowired
    public DevServerPortAllocator(DevServerRuntimeProperties properties) {
        this(properties.getPortRangeStart(), properties.getPortRangeEnd());
    }

    DevServerPortAllocator(int rangeStart, int rangeEnd) {
        if (rangeStart < 1 || rangeEnd > 65535 || rangeStart > rangeEnd) {
            throw new IllegalArgumentException("Dev Server 端口范围无效");
        }
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.nextCandidate = rangeStart;
    }

    /**
 * 返回{@code reserve}。
 *
 * @param appId 应用编号
 * @param preferredPort {@code preferredPort} 对应的调用参数
 * @return 计算或处理后的数值结果
 */
    public int reserve(Long appId, Integer preferredPort) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("应用 ID 必须大于 0");
        }
        allocationLock.lock();
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            Integer existingPort = appPorts.get(appId);
            if (existingPort != null) {
                return existingPort;
            }
            if (isWithinRange(preferredPort)
                    && !appPorts.containsValue(preferredPort)
                    && isPortAvailable(preferredPort)) {
                appPorts.put(appId, preferredPort);
                log.info("为应用 {} 保留首选 Dev Server 端口 {}", appId, preferredPort);
                return preferredPort;
            }

            int candidateCount = rangeEnd - rangeStart + 1;
            for (int attempts = 0; attempts < candidateCount; attempts++) {
                int candidate = takeNextCandidate();
                if (!appPorts.containsValue(candidate) && isPortAvailable(candidate)) {
                    appPorts.put(appId, candidate);
                    log.info("为应用 {} 分配 Dev Server 端口 {}", appId, candidate);
                    return candidate;
                }
            }
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "无法分配可用端口，端口池已满");
        } finally {
            allocationLock.unlock();
        }
    }

    /**
 * 释放开发服务器端口{@code Allocator}。
 *
 * @param appId 应用编号
 */
    public void release(Long appId) {
        if (appId == null) {
            return;
        }
        allocationLock.lock();
        try {
            Integer releasedPort = appPorts.remove(appId);
            if (releasedPort != null) {
                log.info("释放应用 {} 的 Dev Server 端口 {}", appId, releasedPort);
            }
        } finally {
            allocationLock.unlock();
        }
    }

    /** 清理开发服务器端口{@code Allocator}。 */
    public void clear() {
        allocationLock.lock();
        try {
            appPorts.clear();
            nextCandidate = rangeStart;
        } finally {
            allocationLock.unlock();
        }
    }

    private int takeNextCandidate() {
        int candidate = nextCandidate;
        nextCandidate = candidate >= rangeEnd ? rangeStart : candidate + 1;
        return candidate;
    }

    private boolean isWithinRange(Integer port) {
        return port != null && port >= rangeStart && port <= rangeEnd;
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(LOOPBACK_ADDRESS, port));
            return true;
        } catch (IOException exception) {
            return false;
        }
    }
}
