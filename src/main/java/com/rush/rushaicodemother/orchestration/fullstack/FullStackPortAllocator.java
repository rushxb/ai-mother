package com.rush.rushaicodemother.orchestration.fullstack;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 为规范的全栈生成工作区分配有界的前端/后端端口。 */
@Component
@RequiredArgsConstructor
public class FullStackPortAllocator {

    private static final int FRONTEND_PORT_START = 17000;
    private static final int FRONTEND_PORT_END = 17999;
    private static final int BACKEND_PORT_START = 18000;
    private static final int BACKEND_PORT_END = 18999;

    private final GenerationWorkspaceService generationWorkspaceService;
    private final Set<Integer> reservedPorts = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<Long, FullStackGenerationContext> allocations = new ConcurrentHashMap<>();

    /**
 * 返回{@code allocate}。
 *
 * @param appId 应用编号
 * @return 全栈端口{@code Allocator}
 */
    public FullStackGenerationContext allocate(Long appId) {
        GenerationWorkspace workspace = generationWorkspaceService.resolve(
                appId,
                CodeGenTypeEnum.FULL_STACK_PROJECT
        );
        return allocate(workspace);
    }

    /**
     * 为已经跨越规范工作区边界的工作区分配端口。
     * 这种重载会阻止编排调用者将受信任的工作空间转换回
     * 独立重建的路径。
     */
    public FullStackGenerationContext allocate(GenerationWorkspace workspace) {
        validateFullStackWorkspace(workspace);
        return allocations.computeIfAbsent(workspace.appId(), ignored -> allocateNewContext(workspace));
    }

    /** 返回{@code allocate}{@code New}上下文。 */
    private FullStackGenerationContext allocateNewContext(GenerationWorkspace workspace) {
        int frontendPort = allocatePort(FRONTEND_PORT_START, FRONTEND_PORT_END);
        try {
            int backendPort = allocatePort(BACKEND_PORT_START, BACKEND_PORT_END);
            return FullStackGenerationContext.create(frontendPort, backendPort, workspace);
        } catch (RuntimeException exception) {
            reservedPorts.remove(frontendPort);
            throw exception;
        }
    }

    private void validateFullStackWorkspace(GenerationWorkspace workspace) {
        if (workspace == null
                || workspace.appId() == null
                || workspace.appId() <= 0
                || workspace.codeGenType() != CodeGenTypeEnum.FULL_STACK_PROJECT
                || workspace.canonicalRootPath() == null
                || workspace.frontendRootPath() == null
                || workspace.backendRootPath() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "全栈生成工作区参数错误");
        }
    }

    /** 返回{@code allocate}端口。 */
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
        } catch (IOException exception) {
            return false;
        }
    }
}
