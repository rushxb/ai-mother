package com.rush.rushaicodemother.application.app;

import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.DevServerStatusVO;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewPathFactory;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewRoute;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewRoutingService;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewSession;
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
    private final DevServerPreviewRoutingService previewRoutingService;
    private final DevServerPreviewPathFactory previewPathFactory;
    private final AppAccessPolicy appAccessPolicy;

    /**
 * 启动应用开发服务器应用。
 *
 * @param appId 应用编号
 * @param actor 操作发起人
 * @return 应用开发服务器应用
 */
    public DevServerStatusVO start(Long appId, User actor) {
        App app = requireOwnedApp(appId, actor, "无权限操作该应用");
        DevServerPreviewSession current = previewRoutingService.findCurrent(appId).orElse(null);
        if (current != null) {
            return status(appId, current, app.getDevServerPort());
        }
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

    /**
 * 停止应用开发服务器应用。
 *
 * @param appId 应用编号
 * @param actor 操作发起人
 */
    public void stop(Long appId, User actor) {
        requireOwnedApp(appId, actor, "无权限操作该应用");
        devServerManager.stopDevServer(appId);
    }

    public DevServerStatusVO getStatus(Long appId, User actor) {
        App app = requireOwnedApp(appId, actor, "无权限访问该应用");
        DevServerPreviewSession current = previewRoutingService.findCurrent(appId).orElse(null);
        return status(appId, current, app.getDevServerPort());
    }

    /**
 * 校验并返回有效的代理{@code Route}。
 *
 * @param appId 应用编号
 * @param actor 操作发起人
 * @return 代理{@code Route}
 */
    public DevServerPreviewRoute requireProxyRoute(Long appId, User actor) {
        requireOwnedApp(appId, actor, "无权限访问该应用");
        return previewRoutingService.requireRunningRoute(appId);
    }

    private void persistPortIfChanged(App app, int port) {
        if (app.getDevServerPort() != null && app.getDevServerPort() == port) {
            return;
        }
        appPersistenceService.updateDevServerPort(app.getId(), port);
    }

    /** 处理补偿已启动服务器。 */
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
                .previewUrl(running ? previewPathFactory.publicBasePath(appId) : null)
                .status(running ? "running" : "stopped")
                .build();
    }

    /** 返回状态。 */
    private DevServerStatusVO status(
            Long appId,
            DevServerPreviewSession session,
            Integer persistedPort
    ) {
        if (session == null) {
            return status(appId, false, persistedPort);
        }
        return DevServerStatusVO.builder()
                .appId(appId)
                .running(session.running())
                .port(session.port())
                .previewUrl(session.running()
                        ? previewPathFactory.publicBasePath(appId)
                        : null)
                .status(session.status())
                .build();
    }
}
