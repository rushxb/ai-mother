package com.rush.rushaicodemother.controller.app.websocket;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.service.devserver.DevServerInternalRequestSigner;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewRoutingService;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewTargetResolver;
import com.rush.rushaicodemother.service.devserver.DevServerWebSocketProxyHandler;
import com.rush.rushaicodemother.service.devserver.DevServerWebSocketUpstream;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/** 验证所有者节点 WebSocket 跃点并根据持久本地租约将其隔离。 */
@Component
public class InternalDevServerWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    private final DevServerWebSocketRequestParser requestParser;
    private final DevServerInternalRequestSigner requestSigner;
    private final DevServerPreviewRoutingService routingService;
    private final DevServerPreviewTargetResolver targetResolver;

    public InternalDevServerWebSocketHandshakeInterceptor(
            DevServerWebSocketRequestParser requestParser,
            DevServerInternalRequestSigner requestSigner,
            DevServerPreviewRoutingService routingService,
            DevServerPreviewTargetResolver targetResolver
    ) {
        this.requestParser = requestParser;
        this.requestSigner = requestSigner;
        this.routingService = routingService;
        this.targetResolver = targetResolver;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return reject(response, HttpStatus.BAD_REQUEST);
        }
        try {
            var verified = requestSigner.verify(servletRequest.getServletRequest());
            requestSigner.verifyBody(verified, new byte[0]);
        } catch (BusinessException invalidSignature) {
            HttpStatus status = invalidSignature.getCode() == ErrorCode.NO_AUTH_ERROR.getCode()
                    ? HttpStatus.FORBIDDEN
                    : HttpStatus.SERVICE_UNAVAILABLE;
            return reject(response, status);
        } catch (RuntimeException verificationFailure) {
            return reject(response, HttpStatus.SERVICE_UNAVAILABLE);
        }
        try {
            DevServerWebSocketRequest parsed = requestParser.parseInternal(
                    servletRequest.getServletRequest());
            int port = routingService.requireLocalRunningPort(parsed.appId());
            var target = targetResolver.localWebSocketTarget(
                    parsed.appId(), port, parsed.targetPath(), parsed.queryString());
            attributes.put(
                    DevServerWebSocketProxyHandler.UPSTREAM_ATTRIBUTE,
                    new DevServerWebSocketUpstream(target, Map.of())
            );
            return true;
        } catch (IllegalArgumentException invalidPath) {
            return reject(response, HttpStatus.BAD_REQUEST);
        } catch (BusinessException unavailableOwner) {
            return reject(response, HttpStatus.SERVICE_UNAVAILABLE);
        } catch (RuntimeException unavailable) {
            return reject(response, HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // 签名随机数状态已被验证者消耗。
    }

    private boolean reject(ServerHttpResponse response, HttpStatus status) {
        response.setStatusCode(status);
        return false;
    }
}
