package com.rush.rushaicodemother.service.devserver;

import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 构建HTTP资产和Vite HMR使用的单一公共路径命名空间。 */
@Component
public class DevServerPreviewPathFactory {

    private final String contextPath;

    @Autowired
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

    /**
 * 返回{@code local}{@code Upstream}路径。
 *
 * @param appId 应用编号
 * @param targetPath 目标路径
 * @return 处理后的开发服务器预览路径文本
 */
    public String localUpstreamPath(Long appId, String targetPath) {
        String normalizedTarget = requireTargetPath(targetPath);
        String relativeTarget = normalizedTarget.length() == 1
                ? ""
                : normalizedTarget.substring(1);
        return publicBasePath(appId) + relativeTarget;
    }

    /** 规范化上下文路径。 */
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
