package com.rush.rushaicodemother.orchestration.fullstack;

import com.rush.rushaicodemother.constant.AppConstant;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FullStackPortAllocator {

    private static final int FRONTEND_PORT_START = 17000;
    private static final int FRONTEND_PORT_END = 17999;
    private static final int BACKEND_PORT_START = 18000;
    private static final int BACKEND_PORT_END = 18999;

    private final Set<Integer> reservedPorts = ConcurrentHashMap.newKeySet();

    public FullStackGenerationContext allocate(Long appId) {
        int frontendPort = allocatePort(FRONTEND_PORT_START, FRONTEND_PORT_END);
        int backendPort = allocatePort(BACKEND_PORT_START, BACKEND_PORT_END);
        Path workspaceRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "full_stack_project_" + appId)
                .toAbsolutePath()
                .normalize();
        return FullStackGenerationContext.create(appId, frontendPort, backendPort, workspaceRoot.toString().replace("\\", "/"));
    }

    private int allocatePort(int startInclusive, int endInclusive) {
        for (int port = startInclusive; port <= endInclusive; port++) {
            if (reservedPorts.contains(port) || !isAvailable(port)) {
                continue;
            }
            if (reservedPorts.add(port)) {
                return port;
            }
        }
        throw new IllegalStateException("端口池已耗尽：" + startInclusive + "-" + endInclusive);
    }

    private boolean isAvailable(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
