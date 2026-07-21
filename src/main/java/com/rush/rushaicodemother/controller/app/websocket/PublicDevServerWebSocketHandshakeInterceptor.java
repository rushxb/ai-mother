package com.rush.rushaicodemother.controller.app.websocket;

import com.rush.rushaicodemother.application.app.AppDevServerApplicationService;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.devserver.DevServerInternalRequestSigner;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewRoute;
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

/** Authorizes browser HMR handshakes and resolves a local or signed owner-node upstream. */
@Component
public class PublicDevServerWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    private final DevServerWebSocketRequestParser requestParser;
    private final UserService userService;
    private final AppDevServerApplicationService applicationService;
    private final DevServerPreviewTargetResolver targetResolver;
    private final DevServerInternalRequestSigner requestSigner;

    public PublicDevServerWebSocketHandshakeInterceptor(
            DevServerWebSocketRequestParser requestParser,
            UserService userService,
            AppDevServerApplicationService applicationService,
            DevServerPreviewTargetResolver targetResolver,
            DevServerInternalRequestSigner requestSigner
    ) {
        this.requestParser = requestParser;
        this.userService = userService;
        this.applicationService = applicationService;
        this.targetResolver = targetResolver;
        this.requestSigner = requestSigner;
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
            DevServerWebSocketRequest parsed = requestParser.parsePublic(
                    servletRequest.getServletRequest());
            User actor = userService.getLoginUser(servletRequest.getServletRequest());
            DevServerPreviewRoute route = applicationService.requireProxyRoute(parsed.appId(), actor);
            var target = targetResolver.webSocketTarget(
                    route, parsed.targetPath(), parsed.queryString());
            Map<String, String> headers = route.local()
                    ? Map.of()
                    : requestSigner.sign("GET", target, new byte[0]);
            attributes.put(
                    DevServerWebSocketProxyHandler.UPSTREAM_ATTRIBUTE,
                    new DevServerWebSocketUpstream(target, headers)
            );
            return true;
        } catch (BusinessException deniedOrUnavailable) {
            HttpStatus status = deniedOrUnavailable.getCode() == ErrorCode.NO_AUTH_ERROR.getCode()
                    ? HttpStatus.FORBIDDEN
                    : HttpStatus.SERVICE_UNAVAILABLE;
            return reject(response, status);
        } catch (IllegalArgumentException invalidRequest) {
            return reject(response, HttpStatus.BAD_REQUEST);
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
        // No browser request state is retained after the trusted upstream is selected.
    }

    private boolean reject(ServerHttpResponse response, HttpStatus status) {
        response.setStatusCode(status);
        return false;
    }
}
