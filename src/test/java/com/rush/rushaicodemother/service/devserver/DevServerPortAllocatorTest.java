package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevServerPortAllocatorTest {

    @Test
    void shouldReuseReservationForSameApplicationAndReleaseIt() throws Exception {
        int port = findAvailableRangeStart(1);
        DevServerPortAllocator allocator = new DevServerPortAllocator(port, port);

        assertEquals(port, allocator.reserve(1L, port));
        assertEquals(port, allocator.reserve(1L, null));

        allocator.release(1L);
        assertEquals(port, allocator.reserve(2L, null));
    }

    @Test
    void shouldAllocateDistinctPortsUnderConcurrency() throws Exception {
        int rangeStart = findAvailableRangeStart(4);
        DevServerPortAllocator allocator = new DevServerPortAllocator(rangeStart, rangeStart + 3);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> tasks = List.of(
                    () -> allocator.reserve(1L, null),
                    () -> allocator.reserve(2L, null),
                    () -> allocator.reserve(3L, null),
                    () -> allocator.reserve(4L, null)
            );
            List<Future<Integer>> futures = executor.invokeAll(tasks);
            Set<Integer> ports = new HashSet<>();
            for (Future<Integer> future : futures) {
                ports.add(future.get());
            }
            assertEquals(4, ports.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldSkipOccupiedPortAndRejectExhaustedPool() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            int occupiedPort = occupied.getLocalPort();
            DevServerPortAllocator allocator = new DevServerPortAllocator(occupiedPort, occupiedPort);

            assertThrows(BusinessException.class, () -> allocator.reserve(1L, occupiedPort));
        }
    }

    @Test
    void shouldIgnorePreferredPortOutsideConfiguredRange() throws Exception {
        int rangeStart = findAvailableRangeStart(2);
        DevServerPortAllocator allocator = new DevServerPortAllocator(rangeStart, rangeStart + 1);

        int allocated = allocator.reserve(1L, rangeStart - 1);

        assertNotEquals(rangeStart - 1, allocated);
    }

    private int findAvailableRangeStart(int size) throws Exception {
        for (int start = 20000; start <= 60000 - size; start += size) {
            java.util.ArrayList<ServerSocket> sockets = new java.util.ArrayList<>();
            try {
                for (int offset = 0; offset < size; offset++) {
                    ServerSocket socket = new ServerSocket();
                    socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), start + offset));
                    sockets.add(socket);
                }
                return start;
            } catch (Exception ignored) {
                // 继续寻找下一段连续端口。
            } finally {
                for (ServerSocket socket : sockets) {
                    socket.close();
                }
            }
        }
        throw new IllegalStateException("未找到可用的测试端口段");
    }
}
