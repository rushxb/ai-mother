package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** 为基准测试后端分配进程内唯一的回环端口租约。 */
@Component
public class GenerationBenchmarkBackendPortAllocator {

    private static final String LOOPBACK_ADDRESS = "127.0.0.1";

    private final int rangeStart;
    private final int rangeEnd;
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<Integer> reservedPorts = new HashSet<>();
    private int nextCandidate;

    @Autowired
    public GenerationBenchmarkBackendPortAllocator(GenerationBenchmarkBackendProperties properties) {
        this(properties.getPortRangeStart(), properties.getPortRangeEnd());
    }

    GenerationBenchmarkBackendPortAllocator(int rangeStart, int rangeEnd) {
        if (rangeStart < 1 || rangeEnd > 65_535 || rangeStart > rangeEnd) {
            throw new IllegalArgumentException("后端运行时端口范围无效");
        }
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.nextCandidate = rangeStart;
    }

    /**
 * 返回{@code reserve}。
 *
 * @return 生成基准测试后端端口{@code Allocator}
 */
    public PortLease reserve() {
        lock.lock();
        try {
            int candidateCount = rangeEnd - rangeStart + 1;
            for (int attempts = 0; attempts < candidateCount; attempts++) {
                int candidate = takeNextCandidate();
                if (reservedPorts.contains(candidate)) {
                    continue;
                }
                ServerSocket binding = tryBind(candidate);
                if (binding == null) {
                    continue;
                }
                reservedPorts.add(candidate);
                return new PortLease(this, candidate, binding);
            }
        } finally {
            lock.unlock();
        }
        throw new IllegalStateException("后端运行时端口池已耗尽");
    }

    private int takeNextCandidate() {
        int candidate = nextCandidate;
        nextCandidate = candidate >= rangeEnd ? rangeStart : candidate + 1;
        return candidate;
    }

    /** 返回{@code try}{@code Bind}。 */
    private ServerSocket tryBind(int port) {
        ServerSocket socket = null;
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(LOOPBACK_ADDRESS, port));
            return socket;
        } catch (IOException exception) {
            closeQuietly(socket);
            return null;
        }
    }

    private void release(int port) {
        lock.lock();
        try {
            reservedPorts.remove(port);
        } finally {
            lock.unlock();
        }
    }

    /** 关闭{@code Quietly}并释放资源。 */
    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // 端口租约关闭是幂等清理，后续系统端口检查仍会阻止冲突分配。
        }
    }

    public static final class PortLease implements AutoCloseable {

        private final GenerationBenchmarkBackendPortAllocator owner;
        private final int port;
        private final ServerSocket binding;
        private final AtomicBoolean bindingReleased = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private PortLease(
                GenerationBenchmarkBackendPortAllocator owner,
                int port,
                ServerSocket binding
        ) {
            this.owner = owner;
            this.port = port;
            this.binding = binding;
        }

        public int port() {
            return port;
        }

        /** 在目标进程启动前释放系统绑定，但保留进程内逻辑租约。 */
        public void releaseBindingForProcessStart() {
            if (bindingReleased.compareAndSet(false, true)) {
                closeQuietly(binding);
            }
        }

        /** 关闭端口租约并释放资源。 */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                releaseBindingForProcessStart();
                owner.release(port);
            }
        }
    }
}
