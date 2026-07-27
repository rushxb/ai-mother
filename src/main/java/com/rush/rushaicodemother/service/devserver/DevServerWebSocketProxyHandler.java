package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerWebSocketProxyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Vite HMR 跨本地或所有者节点预览路由的有界双向桥。 */
@Slf4j
@Component
public class DevServerWebSocketProxyHandler extends AbstractWebSocketHandler
        implements SubProtocolCapable {

    public static final String UPSTREAM_ATTRIBUTE =
            DevServerWebSocketProxyHandler.class.getName() + ".upstream";

    private static final List<String> SUPPORTED_PROTOCOLS = List.of("vite-hmr", "vite-ping");
    private static final CloseStatus UPSTREAM_UNAVAILABLE = new CloseStatus(
            CloseStatus.SERVER_ERROR.getCode(),
            "Preview upstream unavailable"
    );

    private final DevServerWebSocketConnector connector;
    private final Duration connectTimeout;
    private final int sendTimeLimitMillis;
    private final int sendBufferSizeBytes;
    private final int maxMessageSizeBytes;
    private final Map<String, Bridge> bridges = new ConcurrentHashMap<>();

    public DevServerWebSocketProxyHandler(
            DevServerWebSocketConnector connector,
            DevServerWebSocketProxyProperties properties
    ) {
        this.connector = connector;
        this.connectTimeout = properties.getConnectTimeout();
        this.sendTimeLimitMillis = properties.sendTimeLimitMillis();
        this.sendBufferSizeBytes = properties.sendBufferSizeBytes();
        this.maxMessageSizeBytes = properties.maxMessageSizeBytes();
    }

    @Override
    public List<String> getSubProtocols() {
        return SUPPORTED_PROTOCOLS;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Object attribute = session.getAttributes().get(UPSTREAM_ATTRIBUTE);
        if (!(attribute instanceof DevServerWebSocketUpstream upstream)) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        configureMessageLimits(session);
        Bridge bridge = new Bridge(session);
        bridges.put(session.getId(), bridge);

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        upstream.headers().forEach(headers::set);
        if (session.getAcceptedProtocol() != null && !session.getAcceptedProtocol().isBlank()) {
            headers.setSecWebSocketProtocol(List.of(session.getAcceptedProtocol()));
        }

        try {
            CompletableFuture<WebSocketSession> connectFuture = connector.connect(
                    upstream.targetUri(),
                    headers,
                    new OutboundHandler(bridge)
            );
            bridge.setConnectFuture(connectFuture);
            connectFuture.orTimeout(
                            connectTimeout.toMillis(),
                            TimeUnit.MILLISECONDS
                    )
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            bridge.fail(UPSTREAM_UNAVAILABLE, failure);
                        }
                    });
        } catch (RuntimeException connectionFailure) {
            bridge.fail(UPSTREAM_UNAVAILABLE, connectionFailure);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        forwardInbound(session, copy(message));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        forwardInbound(session, copy(message));
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        forwardInbound(session, copy(message));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Bridge bridge = bridges.get(session.getId());
        if (bridge != null) {
            bridge.fail(CloseStatus.SERVER_ERROR, exception);
        } else {
            closeQuietly(session, CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Bridge bridge = bridges.remove(session.getId());
        if (bridge != null) {
            bridge.closeFromInbound(status == null ? CloseStatus.NORMAL : status);
        }
    }

    private void forwardInbound(WebSocketSession session, WebSocketMessage<?> message) {
        Bridge bridge = bridges.get(session.getId());
        if (bridge == null) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        bridge.forwardInbound(message);
    }

    private void configureMessageLimits(WebSocketSession session) {
        session.setTextMessageSizeLimit(maxMessageSizeBytes);
        session.setBinaryMessageSizeLimit(maxMessageSizeBytes);
    }

    private WebSocketMessage<?> copy(WebSocketMessage<?> message) {
        if (message instanceof TextMessage textMessage) {
            return new TextMessage(textMessage.getPayload(), textMessage.isLast());
        }
        byte[] payload = bytes(message);
        if (message instanceof BinaryMessage binaryMessage) {
            return new BinaryMessage(payload, binaryMessage.isLast());
        }
        if (message instanceof PongMessage) {
            return new PongMessage(ByteBuffer.wrap(payload));
        }
        throw new IllegalArgumentException("unsupported Preview WebSocket message type");
    }

    private byte[] bytes(WebSocketMessage<?> message) {
        ByteBuffer source = ((ByteBuffer) message.getPayload()).asReadOnlyBuffer();
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        return bytes;
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(status);
        } catch (IOException | RuntimeException ignored) {
            log.debug("Failed to close Dev Server Preview WebSocket session {}", session.getId());
        }
    }

    private final class OutboundHandler extends AbstractWebSocketHandler {

        private final Bridge bridge;

        private OutboundHandler(Bridge bridge) {
            this.bridge = bridge;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            configureMessageLimits(session);
            bridge.attachOutbound(session);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            bridge.forwardOutbound(copy(message));
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            bridge.forwardOutbound(copy(message));
        }

        @Override
        protected void handlePongMessage(WebSocketSession session, PongMessage message) {
            bridge.forwardOutbound(copy(message));
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            bridge.fail(CloseStatus.SERVER_ERROR, exception);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            bridge.closeFromOutbound(status == null ? CloseStatus.NORMAL : status);
        }
    }

    private final class Bridge {

        private final WebSocketSession inbound;
        private final Object upstreamMonitor = new Object();
        private final Object browserMonitor = new Object();
        private final Deque<WebSocketMessage<?>> pendingInbound = new ArrayDeque<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private WebSocketSession outbound;
        private CompletableFuture<WebSocketSession> connectFuture;
        private int pendingInboundBytes;

        private Bridge(WebSocketSession inbound) {
            this.inbound = decorate(inbound);
        }

        private void setConnectFuture(CompletableFuture<WebSocketSession> connectFuture) {
            this.connectFuture = connectFuture;
        }

        private void attachOutbound(WebSocketSession session) {
            WebSocketSession decorated = decorate(session);
            Throwable sendFailure = null;
            synchronized (upstreamMonitor) {
                if (closed.get()) {
                    closeQuietly(decorated, CloseStatus.NORMAL);
                    return;
                }
                outbound = decorated;
                try {
                    while (!pendingInbound.isEmpty()) {
                        send(outbound, pendingInbound.removeFirst());
                    }
                    pendingInboundBytes = 0;
                } catch (IOException failure) {
                    sendFailure = failure;
                }
            }
            if (sendFailure != null) {
                fail(CloseStatus.SERVER_ERROR, sendFailure);
            }
        }

        private void forwardInbound(WebSocketMessage<?> message) {
            if (!validMessageSize(message)) {
                fail(CloseStatus.TOO_BIG_TO_PROCESS, null);
                return;
            }
            Throwable sendFailure = null;
            boolean bufferOverflow = false;
            synchronized (upstreamMonitor) {
                if (closed.get()) {
                    return;
                }
                if (outbound == null) {
                    long nextSize = (long) pendingInboundBytes + message.getPayloadLength();
                    if (nextSize > sendBufferSizeBytes) {
                        bufferOverflow = true;
                    } else {
                        pendingInbound.addLast(message);
                        pendingInboundBytes = Math.toIntExact(nextSize);
                    }
                } else {
                    try {
                        send(outbound, message);
                    } catch (IOException failure) {
                        sendFailure = failure;
                    }
                }
            }
            if (bufferOverflow) {
                fail(CloseStatus.POLICY_VIOLATION, null);
            } else if (sendFailure != null) {
                fail(CloseStatus.SERVER_ERROR, sendFailure);
            }
        }

        private void forwardOutbound(WebSocketMessage<?> message) {
            if (!validMessageSize(message)) {
                fail(CloseStatus.TOO_BIG_TO_PROCESS, null);
                return;
            }
            Throwable sendFailure = null;
            synchronized (browserMonitor) {
                if (closed.get()) {
                    return;
                }
                try {
                    send(inbound, message);
                } catch (IOException failure) {
                    sendFailure = failure;
                }
            }
            if (sendFailure != null) {
                fail(CloseStatus.SERVER_ERROR, sendFailure);
            }
        }

        private boolean validMessageSize(WebSocketMessage<?> message) {
            return message != null && message.getPayloadLength() <= maxMessageSizeBytes;
        }

        private void send(WebSocketSession session, WebSocketMessage<?> message) throws IOException {
            if (!session.isOpen()) {
                throw new IOException("Preview WebSocket session is closed");
            }
            try {
                session.sendMessage(message);
            } catch (RuntimeException sendFailure) {
                throw new IOException("Preview WebSocket send failed", sendFailure);
            }
        }

        private void fail(CloseStatus status, Throwable failure) {
            if (failure != null) {
                log.debug("Dev Server Preview WebSocket bridge failed: {}",
                        failure.getClass().getSimpleName());
            }
            closeBoth(status == null ? CloseStatus.SERVER_ERROR : status);
        }

        private void closeFromInbound(CloseStatus status) {
            if (closed.compareAndSet(false, true)) {
                cancelConnect();
                clearPending();
                closeQuietly(outbound, status);
            }
        }

        private void closeFromOutbound(CloseStatus status) {
            if (closed.compareAndSet(false, true)) {
                cancelConnect();
                clearPending();
                closeQuietly(inbound, status);
                bridges.remove(inbound.getId(), this);
            }
        }

        private void closeBoth(CloseStatus status) {
            if (closed.compareAndSet(false, true)) {
                cancelConnect();
                clearPending();
                closeQuietly(outbound, status);
                closeQuietly(inbound, status);
                bridges.remove(inbound.getId(), this);
            }
        }

        private void cancelConnect() {
            CompletableFuture<WebSocketSession> future = connectFuture;
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }

        private void clearPending() {
            synchronized (upstreamMonitor) {
                pendingInbound.clear();
                pendingInboundBytes = 0;
            }
        }

        private WebSocketSession decorate(WebSocketSession session) {
            return new ConcurrentWebSocketSessionDecorator(
                    session,
                    sendTimeLimitMillis,
                    sendBufferSizeBytes
            );
        }
    }
}
