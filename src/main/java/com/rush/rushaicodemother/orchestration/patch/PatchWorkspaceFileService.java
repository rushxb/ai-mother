package com.rush.rushaicodemother.orchestration.patch;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.PatchExecutionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/** 无需遵循符号链接即可解析和更改补丁文件。 */
@Component
@RequiredArgsConstructor
public class PatchWorkspaceFileService {

    private final PatchExecutionProperties properties;

    public Path resolveProjectRoot(Path projectRoot) throws PatchWorkspaceException {
        return resolveRealRoot(projectRoot);
    }

    public PatchWorkspaceTarget resolve(Path projectRoot, String relativePath) throws PatchWorkspaceException {
        Path realRoot = resolveRealRoot(projectRoot);
        String normalizedRelativePath = normalizeRelativePath(relativePath);
        Path target = realRoot.resolve(normalizedRelativePath).normalize();
        if (!target.startsWith(realRoot)) {
            throw new PatchWorkspaceException("path_outside_project");
        }
        verifyExistingSegments(realRoot, target);
        return new PatchWorkspaceTarget(realRoot, normalizedRelativePath, target);
    }

    public boolean exists(PatchWorkspaceTarget target) throws PatchWorkspaceException {
        PatchWorkspaceTarget current = revalidate(target);
        return Files.exists(current.absolutePath(), LinkOption.NOFOLLOW_LINKS);
    }

    public boolean isRegularFile(PatchWorkspaceTarget target) throws PatchWorkspaceException {
        PatchWorkspaceTarget current = revalidate(target);
        return Files.isRegularFile(current.absolutePath(), LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(current.absolutePath());
    }

    public boolean isDirectory(PatchWorkspaceTarget target) throws PatchWorkspaceException {
        PatchWorkspaceTarget current = revalidate(target);
        return Files.isDirectory(current.absolutePath(), LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(current.absolutePath());
    }

    public String fileName(PatchWorkspaceTarget target) {
        Path fileName = target == null ? null : target.absolutePath().getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    public void validateWritableUtf8(String content) throws PatchWorkspaceException {
        encodedContent(content);
    }

    public String readUtf8(PatchWorkspaceTarget target) throws IOException {
        return readUtf8(target, properties.getMaxReadableFileBytes());
    }

    /**
     * 读取 UTF-8 文件，同时强制执行调用者限制和全局补丁工作空间限制。
     */
    public String readUtf8(PatchWorkspaceTarget target, long maxReadableBytes) throws IOException {
        if (maxReadableBytes <= 0) {
            throw new PatchWorkspaceException("invalid_read_limit");
        }
        long effectiveLimit = Math.min(maxReadableBytes, properties.getMaxReadableFileBytes());
        PatchWorkspaceTarget current = requireRegularFile(target);
        Set<java.nio.file.OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(current.absolutePath(), options)) {
            long size = channel.size();
            if (size > effectiveLimit || size > Integer.MAX_VALUE) {
                throw new PatchWorkspaceException("target_file_too_large");
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            int consecutiveEmptyReads = 0;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    consecutiveEmptyReads++;
                    if (consecutiveEmptyReads >= 16) {
                        throw new PatchWorkspaceException("target_file_read_stalled");
                    }
                    continue;
                }
                consecutiveEmptyReads = 0;
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0) {
                throw new PatchWorkspaceException("target_file_changed_during_read");
            }
            buffer.flip();
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(buffer)
                        .toString();
            } catch (CharacterCodingException exception) {
                throw new PatchWorkspaceException("invalid_utf8_content", exception);
            }
        } catch (PatchWorkspaceException exception) {
            throw exception;
        } catch (IOException | SecurityException | UnsupportedOperationException exception) {
            throw new PatchWorkspaceException("read_target_failed", exception);
        }
    }

    public void writeNewUtf8(PatchWorkspaceTarget target, String content) throws IOException {
        byte[] bytes = encodedContent(content);
        PatchWorkspaceTarget current = revalidate(target);
        ensureParentDirectories(current);
        current = revalidate(current);
        Set<java.nio.file.OpenOption> options = Set.of(
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW,
                LinkOption.NOFOLLOW_LINKS
        );
        writeBytes(current.absolutePath(), bytes, options);
    }

    public void overwriteUtf8(PatchWorkspaceTarget target, String content) throws IOException {
        byte[] bytes = encodedContent(content);
        PatchWorkspaceTarget current = requireRegularFile(target);
        Set<java.nio.file.OpenOption> options = Set.of(
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS
        );
        writeBytes(current.absolutePath(), bytes, options);
    }

    public void delete(PatchWorkspaceTarget target) throws IOException {
        PatchWorkspaceTarget current = requireRegularFile(target);
        try {
            Files.delete(current.absolutePath());
        } catch (IOException | SecurityException exception) {
            throw new PatchWorkspaceException("delete_target_failed", exception);
        }
    }

    private PatchWorkspaceTarget requireRegularFile(PatchWorkspaceTarget target) throws PatchWorkspaceException {
        PatchWorkspaceTarget current = revalidate(target);
        if (!Files.isRegularFile(current.absolutePath(), LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(current.absolutePath())) {
            throw new PatchWorkspaceException("target_not_regular_file");
        }
        return current;
    }

    private PatchWorkspaceTarget revalidate(PatchWorkspaceTarget target) throws PatchWorkspaceException {
        if (target == null) {
            throw new PatchWorkspaceException("invalid_path");
        }
        PatchWorkspaceTarget current = resolve(target.realRoot(), target.relativePath());
        if (!current.absolutePath().equals(target.absolutePath())) {
            throw new PatchWorkspaceException("path_changed_during_patch");
        }
        return current;
    }

    private Path resolveRealRoot(Path projectRoot) throws PatchWorkspaceException {
        if (projectRoot == null) {
            throw new PatchWorkspaceException("project_root_missing");
        }
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalizedRoot)) {
            throw new PatchWorkspaceException("unsafe_project_root");
        }
        try {
            return normalizedRoot.toRealPath();
        } catch (IOException | SecurityException exception) {
            throw new PatchWorkspaceException("unsafe_project_root", exception);
        }
    }

    private String normalizeRelativePath(String relativePath) throws PatchWorkspaceException {
        if (StrUtil.isBlank(relativePath) || relativePath.indexOf('\0') >= 0) {
            throw new PatchWorkspaceException("invalid_path");
        }
        try {
            Path suppliedPath = Path.of(relativePath.trim().replace('\\', '/'));
            if (suppliedPath.isAbsolute()) {
                throw new PatchWorkspaceException("path_outside_project");
            }
            for (Path segment : suppliedPath) {
                if ("..".equals(segment.toString())) {
                    throw new PatchWorkspaceException("path_outside_project");
                }
            }
            Path normalizedPath = suppliedPath.normalize();
            if (normalizedPath.getNameCount() == 0 || normalizedPath.startsWith("..")) {
                throw new PatchWorkspaceException("invalid_path");
            }
            return normalizedPath.toString().replace('\\', '/');
        } catch (InvalidPathException | SecurityException exception) {
            throw new PatchWorkspaceException("invalid_path", exception);
        }
    }

    private void verifyExistingSegments(Path realRoot, Path target) throws PatchWorkspaceException {
        Path current = realRoot;
        for (Path segment : realRoot.relativize(target)) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (Files.isSymbolicLink(current)) {
                throw new PatchWorkspaceException("symbolic_link_not_allowed");
            }
            try {
                if (!current.toRealPath().startsWith(realRoot)) {
                    throw new PatchWorkspaceException("path_outside_project");
                }
            } catch (PatchWorkspaceException exception) {
                throw exception;
            } catch (IOException | SecurityException exception) {
                throw new PatchWorkspaceException("unsafe_target_path", exception);
            }
        }
    }

    private void ensureParentDirectories(PatchWorkspaceTarget target) throws IOException {
        Path parent = target.absolutePath().getParent();
        if (parent == null || parent.equals(target.realRoot())) {
            return;
        }
        Path current = target.realRoot();
        for (Path segment : target.realRoot().relativize(parent)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new PatchWorkspaceException("unsafe_parent_directory");
                }
                continue;
            }
            try {
                Files.createDirectory(current);
            } catch (FileAlreadyExistsException ignored) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new PatchWorkspaceException("unsafe_parent_directory");
                }
            } catch (IOException | SecurityException exception) {
                throw new PatchWorkspaceException("create_parent_directory_failed", exception);
            }
        }
        verifyExistingSegments(target.realRoot(), parent);
    }

    private byte[] encodedContent(String content) throws PatchWorkspaceException {
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.getMaxWrittenFileBytes()) {
            throw new PatchWorkspaceException("output_file_too_large");
        }
        return bytes;
    }

    private void writeBytes(Path path,
                            byte[] bytes,
                            Set<java.nio.file.OpenOption> options) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path, options)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException | SecurityException | UnsupportedOperationException exception) {
            throw new PatchWorkspaceException("write_target_failed", exception);
        }
    }
}

