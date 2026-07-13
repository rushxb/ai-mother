package com.rush.rushaicodemother.infrastructure.filesystem;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 受限文件路径解析器。
 * 在跟随符号链接后的真实路径上校验目录边界，避免目录穿越和符号链接越界。
 */
@Component
public class SecurePathResolver {

    private static final Pattern SAFE_SCOPE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$");

    /**
     * 在指定根目录和隔离子目录中解析一个已存在的普通文件。
     *
     * @param rootDirectory 受信任根目录
     * @param scopeName     根目录下的隔离子目录名称
     * @param relativePath  子目录内的相对文件路径
     * @return 通过真实路径边界校验的普通文件
     */
    public Path resolveRegularFile(Path rootDirectory, String scopeName, String relativePath) throws IOException {
        validateScopeName(scopeName);
        Path safeRelativePath = parseRelativePath(relativePath);

        Path realRoot = rootDirectory.toRealPath();
        Path declaredScope = realRoot.resolve(scopeName).normalize();
        ensureWithin(declaredScope, realRoot);

        Path realScope = declaredScope.toRealPath();
        ensureWithin(realScope, realRoot);

        Path declaredCandidate = realScope.resolve(safeRelativePath).normalize();
        ensureWithin(declaredCandidate, realScope);

        Path realCandidate = declaredCandidate.toRealPath();
        ensureWithin(realCandidate, realScope);
        if (!Files.isRegularFile(realCandidate)) {
            throw new java.nio.file.NoSuchFileException(realCandidate.toString());
        }
        return realCandidate;
    }

    private void validateScopeName(String scopeName) {
        if (scopeName == null || !SAFE_SCOPE_NAME.matcher(scopeName).matches()) {
            throw new IllegalArgumentException("非法资源目录标识");
        }
    }

    private Path parseRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("资源路径不能为空");
        }
        Path path = Path.of(relativePath);
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("资源路径必须是相对路径");
        }
        Path normalized = path.normalize();
        if (normalized.getNameCount() == 0 || normalized.startsWith("..")) {
            throw new IllegalArgumentException("资源路径超出允许目录");
        }
        return normalized;
    }

    private void ensureWithin(Path candidate, Path boundary) {
        if (!candidate.startsWith(boundary)) {
            throw new IllegalArgumentException("资源路径超出允许目录");
        }
    }
}
