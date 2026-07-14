package com.rush.rushaicodemother.core.builder;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 扫描 Vue 项目并生成强校验指纹。扫描不会跟随符号链接，并受文件数量和单文件大小上限保护。
 */
@Component
public class VueProjectSnapshotService {

    private static final int BUFFER_SIZE = 16 * 1024;

    private final WorkspaceFileSystemProperties properties;

    public VueProjectSnapshotService(WorkspaceFileSystemProperties properties) {
        this.properties = properties;
    }

    VueProjectSnapshot capture(Path projectRoot, JSONObject packageJson) throws IOException {
        if (!Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(projectRoot)) {
            throw new IOException("Vue 项目根目录无效或为符号链接");
        }

        List<String> dependencyEntries = new ArrayList<>();
        List<String> criticalEntries = new ArrayList<>();
        List<String> presentationEntries = new ArrayList<>();
        appendPackageDependencyFingerprint(dependencyEntries, packageJson);
        appendPackageScriptFingerprint(criticalEntries, packageJson);

        AtomicInteger visitedFileCount = new AtomicInteger();
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(projectRoot) && shouldSkipDirectory(projectRoot.relativize(directory))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                int currentFileCount = visitedFileCount.incrementAndGet();
                if (currentFileCount > properties.getMaxFiles()) {
                    throw new IOException("Vue 项目文件数量超过快照上限: " + properties.getMaxFiles());
                }

                Path relativePath = projectRoot.relativize(file);
                String normalizedPath = normalizePath(relativePath);
                String classificationPath = normalizedPath.toLowerCase(Locale.ROOT);
                if (VueBuildStateStore.isManagedStateFile(classificationPath)
                        || "package.json".equals(classificationPath)) {
                    return FileVisitResult.CONTINUE;
                }
                if (isDependencyFile(classificationPath)) {
                    appendFileFingerprint(dependencyEntries, normalizedPath, file, attributes.size());
                } else if (isPresentationFile(classificationPath)) {
                    appendFileFingerprint(presentationEntries, normalizedPath, file, attributes.size());
                } else if (isCriticalFile(classificationPath)) {
                    appendFileFingerprint(criticalEntries, normalizedPath, file, attributes.size());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return new VueProjectSnapshot(
                hashEntries(dependencyEntries),
                hashEntries(criticalEntries),
                hashEntries(presentationEntries)
        );
    }

    private void appendPackageDependencyFingerprint(List<String> entries, JSONObject packageJson) {
        appendJsonSectionFingerprint(entries, "dependencies", packageJson.get("dependencies"));
        appendJsonSectionFingerprint(entries, "devDependencies", packageJson.get("devDependencies"));
        appendJsonSectionFingerprint(entries, "peerDependencies", packageJson.get("peerDependencies"));
        appendJsonSectionFingerprint(entries, "optionalDependencies", packageJson.get("optionalDependencies"));
        appendJsonSectionFingerprint(entries, "overrides", packageJson.get("overrides"));
        appendJsonSectionFingerprint(entries, "resolutions", packageJson.get("resolutions"));
        appendJsonSectionFingerprint(entries, "engines", packageJson.get("engines"));
        appendJsonSectionFingerprint(entries, "pnpm", packageJson.get("pnpm"));
        appendJsonSectionFingerprint(entries, "workspaces", packageJson.get("workspaces"));
        entries.add("packageManager:" + canonicalJson(packageJson.get("packageManager")));
    }

    private void appendPackageScriptFingerprint(List<String> entries, JSONObject packageJson) {
        appendJsonSectionFingerprint(entries, "scripts", packageJson.get("scripts"));
    }

    private void appendJsonSectionFingerprint(List<String> entries, String sectionName, Object value) {
        entries.add(sectionName + ':' + canonicalJson(value));
    }

    private String canonicalJson(Object value) {
        if (value instanceof JSONObject object) {
            List<String> keys = new ArrayList<>(object.keySet());
            Collections.sort(keys);
            StringBuilder builder = new StringBuilder("{");
            for (String key : keys) {
                builder.append(JSONUtil.quote(key))
                        .append(':')
                        .append(canonicalJson(object.get(key)))
                        .append(',');
            }
            return builder.append('}').toString();
        }
        if (value instanceof JSONArray array) {
            StringBuilder builder = new StringBuilder("[");
            for (Object item : array) {
                builder.append(canonicalJson(item)).append(',');
            }
            return builder.append(']').toString();
        }
        return JSONUtil.toJsonStr(value);
    }

    private void appendFileFingerprint(List<String> entries, String normalizedPath, Path file, long declaredSize)
            throws IOException {
        long maxFileBytes = properties.getMaxFileBytes();
        if (declaredSize > maxFileBytes) {
            throw new IOException("Vue 项目文件超过快照大小上限: " + normalizedPath);
        }

        MessageDigest digest = newSha256Digest();
        long bytesRead = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream inputStream = Files.newInputStream(file)) {
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                bytesRead += read;
                if (bytesRead > maxFileBytes) {
                    throw new IOException("Vue 项目文件超过快照大小上限: " + normalizedPath);
                }
                digest.update(buffer, 0, read);
            }
        }
        entries.add(normalizedPath + ':' + bytesRead + ':' + HexFormat.of().formatHex(digest.digest()));
    }

    private String hashEntries(List<String> entries) {
        Collections.sort(entries);
        MessageDigest digest = newSha256Digest();
        for (String entry : entries) {
            digest.update(entry.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    private String normalizePath(Path relativePath) {
        return relativePath.toString().replace('\\', '/');
    }

    private boolean shouldSkipDirectory(Path relativePath) {
        String normalized = normalizePath(relativePath).toLowerCase(Locale.ROOT);
        return isDirectoryToken(normalized, "node_modules")
                || isDirectoryToken(normalized, "dist")
                || isDirectoryToken(normalized, "coverage")
                || isDirectoryToken(normalized, "target")
                || isDirectoryToken(normalized, "build")
                || isDirectoryToken(normalized, "out")
                || isDirectoryToken(normalized, ".git")
                || isDirectoryToken(normalized, ".idea")
                || isDirectoryToken(normalized, ".vscode")
                || isDirectoryToken(normalized, ".cache")
                || isDirectoryToken(normalized, ".turbo");
    }

    private boolean isDirectoryToken(String normalizedPath, String token) {
        return normalizedPath.equals(token)
                || normalizedPath.startsWith(token + "/")
                || normalizedPath.contains("/" + token + "/");
    }

    private boolean isDependencyFile(String normalizedPath) {
        return normalizedPath.equals("package-lock.json")
                || normalizedPath.equals("pnpm-lock.yaml")
                || normalizedPath.equals("pnpm-workspace.yaml")
                || normalizedPath.equals("yarn.lock")
                || normalizedPath.equals(".npmrc")
                || normalizedPath.equals(".yarnrc")
                || normalizedPath.equals(".yarnrc.yml")
                || normalizedPath.equals(".pnpmfile.cjs");
    }

    private boolean isPresentationFile(String normalizedPath) {
        return normalizedPath.equals("index.html")
                || normalizedPath.startsWith("public/")
                || normalizedPath.endsWith(".vue")
                || normalizedPath.endsWith(".css")
                || normalizedPath.endsWith(".scss")
                || normalizedPath.endsWith(".less")
                || normalizedPath.endsWith(".sass")
                || normalizedPath.endsWith(".styl")
                || normalizedPath.endsWith(".html")
                || normalizedPath.endsWith(".svg")
                || normalizedPath.endsWith(".png")
                || normalizedPath.endsWith(".jpg")
                || normalizedPath.endsWith(".jpeg")
                || normalizedPath.endsWith(".gif")
                || normalizedPath.endsWith(".webp")
                || normalizedPath.endsWith(".ico")
                || normalizedPath.endsWith(".avif")
                || normalizedPath.endsWith(".bmp");
    }

    private boolean isCriticalFile(String normalizedPath) {
        if (normalizedPath.startsWith("vite.config.")
                || normalizedPath.startsWith("vue.config.")
                || normalizedPath.startsWith("tsconfig")
                || normalizedPath.startsWith("eslint.config.")
                || normalizedPath.startsWith(".eslintrc")
                || normalizedPath.startsWith("prettier.config.")
                || normalizedPath.startsWith(".prettierrc")
                || normalizedPath.startsWith("postcss.config.")
                || normalizedPath.startsWith("tailwind.config.")
                || normalizedPath.startsWith(".env")) {
            return true;
        }
        if (!normalizedPath.startsWith("src/")) {
            return false;
        }
        return normalizedPath.endsWith(".js")
                || normalizedPath.endsWith(".mjs")
                || normalizedPath.endsWith(".cjs")
                || normalizedPath.endsWith(".ts")
                || normalizedPath.endsWith(".mts")
                || normalizedPath.endsWith(".cts")
                || normalizedPath.endsWith(".jsx")
                || normalizedPath.endsWith(".tsx")
                || normalizedPath.endsWith(".d.ts")
                || normalizedPath.endsWith(".json");
    }
}
