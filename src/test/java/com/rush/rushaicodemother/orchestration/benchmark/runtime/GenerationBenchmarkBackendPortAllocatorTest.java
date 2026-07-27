package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationBenchmarkBackendPortAllocatorTest {

    @Test
    void leaseMustHoldSystemBindingUntilProcessStartAndReleaseLogicalReservationOnClose()
            throws IOException {
        int port = findAvailablePort();
        GenerationBenchmarkBackendPortAllocator allocator =
                new GenerationBenchmarkBackendPortAllocator(port, port);

        GenerationBenchmarkBackendPortAllocator.PortLease lease = allocator.reserve();
        assertEquals(port, lease.port());
        assertThrows(IOException.class, () -> bind(port));
        assertThrows(IllegalStateException.class, allocator::reserve);

        lease.releaseBindingForProcessStart();
        try (ServerSocket ignored = bind(port)) {
            assertThrows(IllegalStateException.class, allocator::reserve);
        }
        lease.close();

        try (GenerationBenchmarkBackendPortAllocator.PortLease reused = allocator.reserve()) {
            assertEquals(port, reused.port());
        }
    }

    private int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private ServerSocket bind(int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return socket;
        } catch (IOException exception) {
            socket.close();
            throw exception;
        }
    }
}
