package com.rush.rushaicodemother.service.devserver;

import org.springframework.stereotype.Component;

import java.net.URI;

/** Resolves trusted Preview routes while preserving Vite's application-scoped public base. */
@Component
public class DevServerPreviewTargetResolver {

    private final DevServerPreviewPathFactory pathFactory;

    public DevServerPreviewTargetResolver(DevServerPreviewPathFactory pathFactory) {
        this.pathFactory = pathFactory;
    }

    public URI httpTarget(DevServerPreviewRoute route, String path, String queryString) {
        requireRoute(route);
        String upstreamPath = route.local()
                ? pathFactory.localUpstreamPath(route.appId(), path)
                : path;
        return route.httpTarget(upstreamPath, queryString);
    }

    public URI webSocketTarget(DevServerPreviewRoute route, String path, String queryString) {
        requireRoute(route);
        String upstreamPath = route.local()
                ? pathFactory.localUpstreamPath(route.appId(), path)
                : path;
        return route.webSocketTarget(upstreamPath, queryString);
    }

    public URI localHttpTarget(Long appId, int port, String path, String queryString) {
        return DevServerPreviewRoute.local(appId, "local", port).httpTarget(
                pathFactory.localUpstreamPath(appId, path),
                queryString
        );
    }

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
