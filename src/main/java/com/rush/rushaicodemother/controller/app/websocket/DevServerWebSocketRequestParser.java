package com.rush.rushaicodemother.controller.app.websocket;

import com.rush.rushaicodemother.service.devserver.DevServerPreviewPaths;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** 严格解析公共和签名内部预览 WebSocket 请求路径。 */
@Component
public class DevServerWebSocketRequestParser {

    public DevServerWebSocketRequest parsePublic(HttpServletRequest request) {
        return parse(request, DevServerPreviewPaths.PUBLIC_PROXY_PREFIX);
    }

    public DevServerWebSocketRequest parseInternal(HttpServletRequest request) {
        return parse(request, DevServerPreviewPaths.INTERNAL_PROXY_PREFIX);
    }

    private DevServerWebSocketRequest parse(HttpServletRequest request, String routePrefix) {
        if (request == null || request.getRequestURI() == null) {
            throw new IllegalArgumentException("Preview WebSocket request is missing");
        }
        String applicationPath = stripContextPath(request.getRequestURI(), request.getContextPath());
        if (!applicationPath.startsWith(routePrefix)) {
            throw new IllegalArgumentException("Preview WebSocket path is invalid");
        }
        String remainder = applicationPath.substring(routePrefix.length());
        int pathSeparator = remainder.indexOf('/');
        String appIdValue = pathSeparator < 0 ? remainder : remainder.substring(0, pathSeparator);
        String targetPath = pathSeparator < 0 ? "/" : remainder.substring(pathSeparator);
        Long appId;
        try {
            appId = Long.valueOf(appIdValue);
        } catch (RuntimeException invalidAppId) {
            throw new IllegalArgumentException("Preview WebSocket application id is invalid");
        }
        if (appId <= 0 || !targetPath.startsWith("/")
                || containsControlCharacter(targetPath)
                || containsControlCharacter(request.getQueryString())) {
            throw new IllegalArgumentException("Preview WebSocket path is invalid");
        }
        return new DevServerWebSocketRequest(appId, targetPath, request.getQueryString());
    }

    private String stripContextPath(String requestUri, String contextPath) {
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath)) {
            return requestUri;
        }
        if (!requestUri.startsWith(contextPath + "/")) {
            throw new IllegalArgumentException("Preview WebSocket context path is invalid");
        }
        return requestUri.substring(contextPath.length());
    }

    private boolean containsControlCharacter(String value) {
        return value != null
                && value.chars().anyMatch(character -> character < 0x20 || character == 0x7F);
    }
}
