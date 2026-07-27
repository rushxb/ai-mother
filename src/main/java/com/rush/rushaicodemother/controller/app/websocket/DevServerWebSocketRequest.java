package com.rush.rushaicodemother.controller.app.websocket;

/** 从 WebSocket 握手路径解析应用程序 ID 和 Vite 相关目标。 */
record DevServerWebSocketRequest(
        Long appId,
        String targetPath,
        String queryString
) {
}
