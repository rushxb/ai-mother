package com.rush.rushaicodemother.orchestration.snapshot;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 快照绑定的逻辑工作区身份。
 *
 * <p>身份只包含可跨工作树稳定复用的应用、工程类型和相对 scope，绝不持久化
 * 物理工作区根路径作为身份。根 scope 统一编码为 {@code .}。</p>
 */
public record SnapshotScope(Long appId, CodeGenTypeEnum workspaceType, String relativePath) {

    private static final int MAX_SCOPE_LENGTH = 512;

    public SnapshotScope {
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("appId must be positive");
        }
        workspaceType = Objects.requireNonNull(workspaceType, "workspaceType must not be null");
        relativePath = normalizeRelativePath(relativePath);
    }

    /** 将工具层空路径和不同分隔符收敛为稳定的 manifest scope。 */
    public static String normalizeRelativePath(String candidate) {
        if (candidate == null || candidate.isBlank() || ".".equals(candidate.trim())) {
            return ".";
        }
        String normalized = candidate.trim().replace('\\', '/');
        if (normalized.length() > MAX_SCOPE_LENGTH
                || normalized.startsWith("/")
                || normalized.endsWith("/")
                || normalized.indexOf('\0') >= 0
                || normalized.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("snapshot scope must be a bounded relative path");
        }
        String[] rawSegments = normalized.split("/", -1);
        List<String> segments = new ArrayList<>(rawSegments.length);
        for (String segment : rawSegments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("snapshot scope contains an unsafe path segment");
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }
}
