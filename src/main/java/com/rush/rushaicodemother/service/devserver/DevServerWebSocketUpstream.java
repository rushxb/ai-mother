package com.rush.rushaicodemother.service.devserver;

import java.net.URI;
import java.util.Map;

/** 受信任的 WebSocket 上游仅在用户或内部节点授权后产生。 */
public record DevServerWebSocketUpstream(
        URI targetUri,
        Map<String, String> headers
) {

    public DevServerWebSocketUpstream {
        if (targetUri == null
                || !("ws".equalsIgnoreCase(targetUri.getScheme())
                || "wss".equalsIgnoreCase(targetUri.getScheme()))
                || targetUri.getHost() == null
                || targetUri.getHost().isBlank()) {
            throw new IllegalArgumentException("invalid Dev Server WebSocket upstream");
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
