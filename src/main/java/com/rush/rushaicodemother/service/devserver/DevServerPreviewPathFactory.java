package com.rush.rushaicodemother.service.devserver;

import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.stereotype.Component;

/** Builds the single public path namespace used by HTTP assets and Vite HMR. */
@Component
public class DevServerPreviewPathFactory {

    private final String contextPath;

    public DevServerPreviewPathFactory(ServerProperties serverProperties) {
        this(serverProperties == null ? null : serverProperties.getServlet().getContextPath());
    }

    DevServerPreviewPathFactory(String contextPath) {
        this.contextPath = normalizeContextPath(contextPath);
    }

    public String publicBasePath(Long appId) {
        requireAppId(appId);
        return contextPath + DevServerPreviewPaths.PUBLIC_PROXY_PREFIX + appId + "/";
    }

    public String localUpstreamPath(Long appId, String targetPath) {
        String normalizedTarget = requireTargetPath(targetPath);
        String relativeTarget = normalizedTarget.length() == 1
                ? ""
                : normalizedTarget.substring(1);
        return publicBasePath(appId) + relativeTarget;
    }

    private String normalizeContextPath(String value) {
        if (value == null || value.isBlank() || "/".equals(value.trim())) {
            return "";
        }
        String normalized = value.trim();
        if (!normalized.startsWith("/") || normalized.endsWith("/")
                || containsControlCharacter(normalized)) {
            throw new IllegalArgumentException("invalid servlet context path for Dev Server Preview");
        }
        return normalized;
    }

    private String requireTargetPath(String value) {
        if (value == null || !value.startsWith("/") || containsControlCharacter(value)) {
            throw new IllegalArgumentException("invalid Dev Server Preview target path");
        }
        return value;
    }

    private void requireAppId(Long appId) {
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("invalid Dev Server Preview application id");
        }
    }

    private boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7F);
    }
}
