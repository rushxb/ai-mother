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
    private final ConcurrentMap<Long, AllocatedPorts> portAllocations = new ConcurrentHashMap<>();

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
        // 端口属于应用级稳定事实；工作区路径属于单次执行轮次，不能随端口一起缓存。
        AllocatedPorts ports = portAllocations.computeIfAbsent(
                workspace.appId(), ignored -> allocateNewPorts());
        return FullStackGenerationContext.create(
                ports.frontendPort(), ports.backendPort(), workspace);
    }

    /**
     * 释放已删除应用持有的稳定端口。
     *
     * <p>该操作幂等，只能在应用数据库删除事务成功提交后调用；否则仍存活应用的
     * 前后端端口可能被其他应用复用。</p>
     *
     * @param appId 已删除的应用编号
     */
    public void release(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 错误");
        }
        AllocatedPorts releasedPorts = portAllocations.remove(appId);
        if (releasedPorts == null) {
            return;
        }
        reservedPorts.remove(releasedPorts.frontendPort());
        reservedPorts.remove(releasedPorts.backendPort());
    }

    /** 为应用分配一组稳定端口。 */
    private AllocatedPorts allocateNewPorts() {
        int frontendPort = allocatePort(FRONTEND_PORT_START, FRONTEND_PORT_END);
        try {
            int backendPort = allocatePort(BACKEND_PORT_START, BACKEND_PORT_END);
            return new AllocatedPorts(frontendPort, backendPort);
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

    private record AllocatedPorts(int frontendPort, int backendPort) {
    }
}
