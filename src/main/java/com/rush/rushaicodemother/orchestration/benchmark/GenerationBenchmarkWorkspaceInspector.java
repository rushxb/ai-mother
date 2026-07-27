package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** 为评测夹具和评分规则提供确定且受工作区约束的文件操作。 */
@Component
public class GenerationBenchmarkWorkspaceInspector {

    public GenerationBenchmarkWorkspaceSnapshot capture(Path root) {
        Path normalizedRoot = normalizeRoot(root);
        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            return new GenerationBenchmarkWorkspaceSnapshot(normalizedRoot, Map.of());
        }
        Map<String, String> digests = new LinkedHashMap<>();
        try (var paths = Files.walk(normalizedRoot)) {
            for (Path path : paths
                    .filter(candidate -> Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                    .filter(candidate -> !excluded(normalizedRoot, candidate))
                    .sorted()
                    .toList()) {
                digests.put(relativePath(normalizedRoot, path), sha256(path));
            }
        } catch (Exception failure) {
            throw new IllegalStateException("无法采集评测工作区快照", failure);
        }
        return new GenerationBenchmarkWorkspaceSnapshot(normalizedRoot, digests);
    }

    public String readUtf8(Path root, String relativePath) {
        Path target = resolve(root, relativePath);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException("无法读取评测工作区文件", failure);
        }
    }

    public String readUtf8(Path root, String relativePath, int maximumChars) {
        if (maximumChars <= 0) {
            throw new IllegalArgumentException("评测文件字符上限必须为正数");
        }
        Path target = resolve(root, relativePath);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        try (Reader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            StringBuilder content = new StringBuilder(Math.min(maximumChars, 8_192));
            char[] buffer = new char[Math.min(maximumChars, 8_192)];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                if (content.length() + read > maximumChars) {
                    throw new IllegalStateException("评测源码文件超过字符上限");
                }
                content.append(buffer, 0, read);
            }
            return content.toString();
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("无法读取评测工作区文件", failure);
        }
    }

    public void writeUtf8(Path root, String relativePath, String content) {
        Path target = resolve(root, relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException("无法写入评测工作区夹具", failure);
        }
    }

    public boolean exists(Path root, String relativePath) {
        return Files.isRegularFile(resolve(root, relativePath), LinkOption.NOFOLLOW_LINKS);
    }

    public Path resolve(Path root, String relativePath) {
        Path normalizedRoot = normalizeRoot(root);
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("评测相对路径不能为空");
        }
        Path target = normalizedRoot.resolve(relativePath.replace('\\', '/')).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("评测路径不能越出工作区");
        }
        rejectSymbolicLinks(normalizedRoot, target);
        return target;
    }

    private void rejectSymbolicLinks(Path root, Path target) {
        Path current = root;
        if (Files.isSymbolicLink(current)) {
            throw new IllegalArgumentException("评测工作区不能使用符号链接");
        }
        for (Path segment : root.relativize(target)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("评测源码路径不能经过符号链接");
            }
        }
    }

    private Path normalizeRoot(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("评测工作区根目录不能为空");
        }
        return root.toAbsolutePath().normalize();
    }

    private boolean excluded(Path root, Path candidate) {
        Path relative = root.relativize(candidate);
        for (Path segment : relative) {
            if (GenerationWorkspaceService.HIDDEN_FILE_NAMES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private String relativePath(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
