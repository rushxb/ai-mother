package com.rush.rushaicodemother.service.devserver;

import org.springframework.stereotype.Component;

import java.net.URI;

/** 解析可信预览路由，同时保留 Vite 应用范围的公共基础。 */
@Component
public class DevServerPreviewTargetResolver {

    private final DevServerPreviewPathFactory pathFactory;

    public DevServerPreviewTargetResolver(DevServerPreviewPathFactory pathFactory) {
        this.pathFactory = pathFactory;
    }

    /**
 * 返回HTTP目标。
 *
 * @param route 代理路由
 * @param path 目标路径
 * @param queryString 原始查询字符串
 * @return 开发服务器预览目标
 */
    public URI httpTarget(DevServerPreviewRoute route, String path, String queryString) {
        requireRoute(route);
        String upstreamPath = route.local()
                ? pathFactory.localUpstreamPath(route.appId(), path)
                : path;
        return route.httpTarget(upstreamPath, queryString);
    }

    /**
 * 返回 WebSocket 目标。
 *
 * @param route 代理路由
 * @param path 目标路径
 * @param queryString 原始查询字符串
 * @return 开发服务器预览目标
 */
    public URI webSocketTarget(DevServerPreviewRoute route, String path, String queryString) {
        requireRoute(route);
        String upstreamPath = route.local()
                ? pathFactory.localUpstreamPath(route.appId(), path)
                : path;
        return route.webSocketTarget(upstreamPath, queryString);
    }

    /**
 * 返回{@code local}HTTP目标。
 *
 * @param appId 应用编号
 * @param port 端口
 * @param path 目标路径
 * @param queryString 原始查询字符串
 * @return 开发服务器预览目标
 */
    public URI localHttpTarget(Long appId, int port, String path, String queryString) {
        return DevServerPreviewRoute.local(appId, "local", port).httpTarget(
                pathFactory.localUpstreamPath(appId, path),
                queryString
        );
    }

    /**
 * 返回{@code local}WebSocket 目标。
 *
 * @param appId 应用编号
 * @param port 端口
 * @param path 目标路径
 * @param queryString 原始查询字符串
 * @return 开发服务器预览目标
 */
    public URI localWebSocketTarget(Long appId, int port, String path, String queryString) {
        return DevServerPreviewRoute.local(appId, "local", port).webSocketTarget(
                pathFactory.localUpstreamPath(appId, path),
                queryString
        );
    }

    private void requireRoute(DevServerPreviewRoute route) {
        if (route == null) {
            throw new IllegalArgumentException("Dev Server Preview route is required");
        }
    }
}
