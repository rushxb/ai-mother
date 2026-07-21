package com.rush.rushaicodemother.controller.app.websocket;

/** Parsed application id and Vite-relative target from a WebSocket handshake path. */
record DevServerWebSocketRequest(
        Long appId,
        String targetPath,
        String queryString
) {
}
