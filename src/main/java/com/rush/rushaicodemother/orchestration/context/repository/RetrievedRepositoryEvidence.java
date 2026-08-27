package com.rush.rushaicodemother.orchestration.context.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从受控工作区读取器获得的项目事实。
 *
 * <p>该对象仍是不可信数据，只能交给 {@link RepositoryContextTrustService} 转换，
 * 不能直接发送给模型。</p>
 */
public record RetrievedRepositoryEvidence(
        String structuralContext,
        List<FileEvidence> files
) {

    public RetrievedRepositoryEvidence {
        structuralContext = structuralContext == null ? "" : structuralContext;
        files = files == null ? List.of() : List.copyOf(files);
    }

    public static RetrievedRepositoryEvidence fromFileContents(
            String structuralContext,
            Map<String, String> fileContents) {
        if (fileContents == null || fileContents.isEmpty()) {
            return new RetrievedRepositoryEvidence(structuralContext, List.of());
        }
        Map<String, FileEvidence> normalized = new LinkedHashMap<>();
        fileContents.forEach((relativePath, content) -> {
            String safePath = normalizeRelativePath(relativePath);
            normalized.putIfAbsent(safePath, new FileEvidence(
                    safePath,
                    content,
                    wasTruncated(content)
            ));
        });
        return new RetrievedRepositoryEvidence(
                structuralContext,
                new ArrayList<>(normalized.values())
        );
    }

    private static String normalizeRelativePath(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.contains(":")) {
            throw new IllegalArgumentException("项目上下文包含非法相对路径");
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..") || segment.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("项目上下文包含非法相对路径: " + normalized);
            }
        }
        return normalized;
    }

    private static boolean wasTruncated(String content) {
        return content != null && (content.contains("文件内容过长，已截断")
                || content.contains("项目上下文已按总预算截断")
                || content.contains("文件内容已按读取预算截断"));
    }

    /** 单个已安全解析路径的文件事实。 */
    public record FileEvidence(String relativePath, String content, boolean truncated) {

        public FileEvidence {
            relativePath = normalizeRelativePath(relativePath);
            content = content == null ? "" : content;
        }
    }
}
