package com.rush.rushaicodemother.orchestration.benchmark;

import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Safe, deterministic file operations shared by benchmark fixture and grader rules. */
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
            throw new IllegalStateException("unable to capture benchmark workspace snapshot", failure);
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
            throw new IllegalStateException("unable to read benchmark workspace file", failure);
        }
    }

    public void writeUtf8(Path root, String relativePath, String content) {
        Path target = resolve(root, relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException("unable to write benchmark workspace fixture", failure);
        }
    }

    public boolean exists(Path root, String relativePath) {
        return Files.isRegularFile(resolve(root, relativePath), LinkOption.NOFOLLOW_LINKS);
    }

    public Path resolve(Path root, String relativePath) {
        Path normalizedRoot = normalizeRoot(root);
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("benchmark relative path is required");
        }
        Path target = normalizedRoot.resolve(relativePath.replace('\\', '/')).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("benchmark path escapes workspace root");
        }
        return target;
    }

    private Path normalizeRoot(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("benchmark workspace root is required");
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
