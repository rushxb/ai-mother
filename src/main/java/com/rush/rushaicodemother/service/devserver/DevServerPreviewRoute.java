package com.rush.rushaicodemother.service.devserver;

import java.net.URI;

/** 从本地运行时或持久所有者节点注册表中选择的可信上游。 */
public record DevServerPreviewRoute(
        Long appId,
        String nodeId,
        int port,
        boolean local,
        URI ownerNodeBaseUri
) {

    public DevServerPreviewRoute {
        if (appId == null || appId <= 0 || nodeId == null || nodeId.isBlank()
                || port < 1 || port > 65535) {
            throw new IllegalArgumentException("invalid Dev Server Preview route");
        }
        if (!local && ownerNodeBaseUri == null) {
            throw new IllegalArgumentException("remote Dev Server Preview route requires an owner endpoint");
        }
    }

    public static DevServerPreviewRoute local(Long appId, String nodeId, int port) {
        return new DevServerPreviewRoute(appId, nodeId, port, true, null);
    }

    public static DevServerPreviewRoute remote(Long appId, String nodeId, int port, URI ownerNodeBaseUri) {
        return new DevServerPreviewRoute(appId, nodeId, port, false, ownerNodeBaseUri);
    }

    public URI httpTarget(String path, String queryString) {
        String targetPath = requirePath(path);
        String base = local
                ? "http://127.0.0.1:" + port
                : ownerNodeBaseUri + DevServerPreviewPaths.INTERNAL_PROXY_PREFIX + appId;
        return appendQuery(URI.create(base + targetPath), queryString);
    }

    public URI webSocketTarget(String path, String queryString) {
        URI httpTarget = httpTarget(path, queryString);
        String scheme = "https".equalsIgnoreCase(httpTarget.getScheme()) ? "wss" : "ws";
        String value = httpTarget.toString();
        return URI.create(scheme + value.substring(value.indexOf(':')));
    }

    private URI appendQuery(URI target, String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return target;
        }
        return URI.create(target + "?" + queryString);
    }

    private String requirePath(String path) {
        if (path == null || !path.startsWith("/") || containsControlCharacter(path)) {
            throw new IllegalArgumentException("invalid Dev Server Preview path");
        }
        return path;
    }

    private boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7F);
    }
}
