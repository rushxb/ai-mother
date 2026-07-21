package com.rush.rushaicodemother.service.devserver;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

/** JSR-356 client adapter for local Vite and signed owner-node WebSocket connections. */
@Component
public class StandardDevServerWebSocketConnector implements DevServerWebSocketConnector {

    private final WebSocketClient client;

    public StandardDevServerWebSocketConnector() {
        this(new StandardWebSocketClient());
    }

    StandardDevServerWebSocketConnector(WebSocketClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<WebSocketSession> connect(
            URI targetUri,
            WebSocketHttpHeaders headers,
            WebSocketHandler handler
    ) {
        return client.execute(handler, headers, targetUri);
    }
}
