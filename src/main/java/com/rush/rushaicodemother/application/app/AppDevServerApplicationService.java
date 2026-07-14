package com.rush.rushaicodemother.application.app;

import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.DevServerStatusVO;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import com.rush.rushaicodemother.service.devserver.DevServerStartResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 应用 Dev Server 编排模块。
 *
 * <p>封装所有权校验、进程管理和端口持久化，控制层只负责 HTTP 协议适配。</p>
 */
@Service
@RequiredArgsConstructor
public class AppDevServerApplicationService {

    private final AppPersistenceService appPersistenceService;
    private final DevServerManager devServerManager;
    private final AppAccessPolicy appAccessPolicy;

    public DevServerStatusVO start(Long appId, User actor) {
        App app = requireOwnedApp(appId, actor, "无权限操作该应用");
        DevServerStartResult startResult = devServerManager.startDevServer(app, actor.getId());
        int port = startResult.port();
        try {
            persistPortIfChanged(app, port);
            return status(appId, true, port);
        } catch (RuntimeException persistenceFailure) {
            compensateStartedServer(appId, startResult, persistenceFailure);
            throw persistenceFailure;
        }
    }

    public void stop(Long appId, User actor) {
        requireOwnedApp(appId, actor, "无权限操作该应用");
        devServerManager.stopDevServer(appId);
    }

    public DevServerStatusVO getStatus(Long appId, User actor) {
        App app = requireOwnedApp(appId, actor, "无权限访问该应用");
        Integer runningPort = devServerManager.getPort(appId);
        return status(
                appId,
                runningPort != null,
                runningPort != null ? runningPort : app.getDevServerPort()
        );
    }

    public int requireProxyPort(Long appId, User actor) {
        requireOwnedApp(appId, actor, "无权限访问该应用");
        Integer port = devServerManager.getPort(appId);
        ThrowUtils.throwIf(port == null || port < 1 || port > 65535,
                ErrorCode.OPERATION_ERROR, "Dev Server 未运行");
        return port;
    }

    private void persistPortIfChanged(App app, int port) {
        if (app.getDevServerPort() != null && app.getDevServerPort() == port) {
            return;
        }
        appPersistenceService.updateDevServerPort(app.getId(), port);
    }

    private void compensateStartedServer(
            Long appId,
            DevServerStartResult startResult,
            RuntimeException persistenceFailure
    ) {
        if (!startResult.startedByCaller()) {
            return;
        }
        try {
            devServerManager.stopDevServer(appId);
        } catch (RuntimeException stopFailure) {
            persistenceFailure.addSuppressed(stopFailure);
        }
    }

    private App requireOwnedApp(Long appId, User actor, String deniedMessage) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        App app = appPersistenceService.findActiveById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        return appAccessPolicy.requireOwner(app, actor, deniedMessage);
    }

    private DevServerStatusVO status(Long appId, boolean running, Integer port) {
        return DevServerStatusVO.builder()
                .appId(appId)
                .running(running)
                .port(port)
                .previewUrl(port == null ? null : String.format("http://localhost:%d", port))
                .status(running ? "running" : "stopped")
                .build();
    }
}
