package com.rush.rushaicodemother.service.devserver;

import java.net.URI;
import java.util.Map;

/** 受信任的 WebSocket 上游仅在用户或内部节点授权后产生。 */
public record DevServerWebSocketUpstream(
        URI targetUri,
        Map<String, String> headers
) {

    /** 创建开发服务器 WebSocket{@code Upstream}实例并完成必要的依赖和初始状态设置。 */
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
