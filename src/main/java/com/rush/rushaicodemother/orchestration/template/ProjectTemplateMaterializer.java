package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.config.TemplateMaterializationProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 在明确的文件系统资源限制下安全地扩展打包的项目模板。 */
@Component
public class ProjectTemplateMaterializer {

    private static final String TEMPLATE_ROOT = "project-templates";
    private static final int COPY_BUFFER_SIZE = 16 * 1024;
    private static final int PUBLISH_MAX_ATTEMPTS = 5;
    private static final long PUBLISH_RETRY_DELAY_MILLIS = 50;

    private final TemplateMaterializationProperties properties;
    private final ProjectTemplateCatalog templateCatalog;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final PathMatchingResourcePatternResolver resourceResolver;

    @Autowired
    public ProjectTemplateMaterializer(TemplateMaterializationProperties properties,
                                       ProjectTemplateCatalog templateCatalog,
                                       WorkspaceFileSystemService workspaceFileSystemService) {
        this(properties, templateCatalog, workspaceFileSystemService, new PathMatchingResourcePatternResolver());
    }

    ProjectTemplateMaterializer(TemplateMaterializationProperties properties,
                                ProjectTemplateCatalog templateCatalog,
                                WorkspaceFileSystemService workspaceFileSystemService,
                                PathMatchingResourcePatternResolver resourceResolver) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.templateCatalog = Objects.requireNonNull(templateCatalog, "templateCatalog must not be null");
        this.workspaceFileSystemService = Objects.requireNonNull(
                workspaceFileSystemService,
                "workspaceFileSystemService must not be null"
        );
        this.resourceResolver = Objects.requireNonNull(resourceResolver, "resourceResolver must not be null");
    }

    /**
     * 在同级暂存目录中具体化模板并发布它而不进行替换。
     * 最终目标在部分复制状态下永远不可见。
     */
    public MaterializationResult materializeAtomically(String templateId,
                                                       Path targetDirectory) throws Exception {
        return materializeAtomically(templateId, targetDirectory, null);
    }

    /**
 * 返回{@code materialize}{@code Atomically}。
 *
 * @param templateId 模板编号
 * @param targetDirectory 目标目录
 * @param customizer {@code customizer} 对应的调用参数
 * @return 项目模板{@code Materializer}
 */
    public MaterializationResult materializeAtomically(String templateId,
                                                       Path targetDirectory,
                                                       StagingCustomizer customizer) throws Exception {
        validateTemplateId(templateId);
        Path target = normalizeTarget(targetDirectory);
        Path parent = target.getParent();
        if (parent == null) {
            throw failure(TemplateMaterializationException.Reason.UNSAFE_TARGET, "Template target has no parent");
        }
        workspaceFileSystemService.ensureDirectory(parent);
        rejectExistingTarget(target);

        Path staging = parent.resolve("." + target.getFileName() + ".template-" + UUID.randomUUID()).normalize();
        if (!staging.getParent().equals(parent)) {
            throw failure(TemplateMaterializationException.Reason.UNSAFE_TARGET, "Template staging path is invalid");
        }
        Exception authoritativeFailure = null;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            Files.createDirectory(staging);
            MaterializationResult stagedResult = materializeContents(templateId, staging);
            if (customizer != null) {
                customizer.customize(staging);
            }
            rejectExistingTarget(target);
            moveWithoutReplace(staging, target);
            return new MaterializationResult(target, stagedResult.fileCount(), stagedResult.totalBytes());
        } catch (TemplateMaterializationException exception) {
            authoritativeFailure = exception;
            throw exception;
        } catch (FileAlreadyExistsException exception) {
            TemplateMaterializationException wrapped = failure(
                    TemplateMaterializationException.Reason.TARGET_ALREADY_EXISTS,
                    "Template target already exists",
                    exception
            );
            authoritativeFailure = wrapped;
            throw wrapped;
        } catch (Exception exception) {
            TemplateMaterializationException wrapped = failure(
                    TemplateMaterializationException.Reason.COPY_FAILED,
                    "Template materialization failed",
                    exception
            );
            authoritativeFailure = wrapped;
            throw wrapped;
        } finally {
            try {
                deleteStagingIfPresent(staging);
            } catch (IOException cleanupFailure) {
                if (authoritativeFailure == null) {
                    throw cleanupFailure;
                }
                authoritativeFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    /** 实现为调用者拥有的现有空目录，例如预热目录。 */
    public MaterializationResult materializeIntoExistingDirectory(String templateId, Path targetDirectory)
            throws IOException {
        validateTemplateId(templateId);
        Path target = requireSafeEmptyDirectory(targetDirectory);
        return materializeContents(templateId, target);
    }

    /** 返回{@code materialize}{@code Contents}。 */
    private MaterializationResult materializeContents(String templateId, Path targetRoot) throws IOException {
        String templatePrefix = TEMPLATE_ROOT + "/" + templateId + "/";
        Resource[] resources;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            resources = resourceResolver.getResources("classpath:" + templatePrefix + "**/*");
        } catch (IOException exception) {
            throw failure(TemplateMaterializationException.Reason.COPY_FAILED, "Template resources cannot be enumerated", exception);
        }

        Set<String> copiedRelativePaths = new HashSet<>();
        MutableCopyTotals totals = new MutableCopyTotals();
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (Resource resource : resources) {
            if (!resource.exists() || !resource.isReadable()) {
                continue;
            }
            String relativePath = resolveRelativePath(resource, templatePrefix);
            if (relativePath.isEmpty() || isDirectoryResource(resource, relativePath)) {
                continue;
            }
            Path relative = validateRelativePath(relativePath);
            String normalizedRelativePath = relative.toString().replace('\\', '/');
            if (!copiedRelativePaths.add(normalizedRelativePath)) {
                throw failure(
                        TemplateMaterializationException.Reason.DUPLICATE_RESOURCE,
                        "Template contains duplicate resource paths"
                );
            }
            if (totals.fileCount >= properties.getMaxFiles()) {
                throw failure(
                        TemplateMaterializationException.Reason.FILE_LIMIT_EXCEEDED,
                        "Template exceeds the configured file-count limit"
                );
            }
            Path targetFile = targetRoot.resolve(relative).normalize();
            if (!targetFile.startsWith(targetRoot) || targetFile.equals(targetRoot)) {
                throw failure(
                        TemplateMaterializationException.Reason.INVALID_RESOURCE_PATH,
                        "Template resource escapes the target directory"
                );
            }
            ensureSafeParentDirectories(targetRoot, targetFile.getParent());
            long copiedBytes = copyBounded(resource, targetFile, totals.totalBytes);
            totals.fileCount++;
            totals.totalBytes += copiedBytes;
        }
        if (totals.fileCount == 0) {
            throw failure(TemplateMaterializationException.Reason.EMPTY_TEMPLATE, "Template contains no readable files");
        }
        return new MaterializationResult(targetRoot, totals.fileCount, totals.totalBytes);
    }

    /** 校验{@code ate}{@code Relative}路径是否有效。 */
    private Path validateRelativePath(String resourcePath) throws TemplateMaterializationException {
        String decoded;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            decoded = URLDecoder.decode(resourcePath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw failure(
                    TemplateMaterializationException.Reason.INVALID_RESOURCE_PATH,
                    "Template resource path has invalid URL encoding",
                    exception
            );
        }
        if (decoded.isBlank()
                || decoded.length() > properties.getMaxRelativePathLength()
                || decoded.startsWith("/")
                || decoded.contains("\\")
                || decoded.indexOf('\0') >= 0) {
            throw failure(
                    TemplateMaterializationException.Reason.INVALID_RESOURCE_PATH,
                    "Template resource path is invalid"
            );
        }
        String[] segments = decoded.split("/", -1);
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw failure(
                        TemplateMaterializationException.Reason.INVALID_RESOURCE_PATH,
                        "Template resource path contains an invalid segment"
                );
            }
        }
        Path relative;
        try {
            relative = Path.of(decoded).normalize();
        } catch (RuntimeException exception) {
            throw failure(
                    TemplateMaterializationException.Reason.INVALID_RESOURCE_PATH,
                    "Template resource path cannot be parsed",
                    exception
            );
        }
        int directoryDepth = Math.max(0, relative.getNameCount() - 1);
        if (relative.isAbsolute() || directoryDepth > properties.getMaxDirectoryDepth()) {
            throw failure(
                    TemplateMaterializationException.Reason.INVALID_RESOURCE_PATH,
                    "Template resource path exceeds the configured boundary"
            );
        }
        return relative;
    }

    /** 复制{@code Bounded}。 */
    private long copyBounded(Resource resource, Path targetFile, long currentTotalBytes) throws IOException {
        long copied = 0L;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream input = resource.getInputStream();
             OutputStream output = Files.newOutputStream(
                     targetFile,
                     StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE,
                     LinkOption.NOFOLLOW_LINKS
             )) {
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                copied = safeAdd(copied, read);
                long updatedTotal = safeAdd(currentTotalBytes, copied);
                if (copied > properties.getMaxFileBytes()) {
                    throw failure(
                            TemplateMaterializationException.Reason.FILE_TOO_LARGE,
                            "Template file exceeds the configured byte limit"
                    );
                }
                if (updatedTotal > properties.getMaxTotalBytes()) {
                    throw failure(
                            TemplateMaterializationException.Reason.TOTAL_BYTES_EXCEEDED,
                            "Template exceeds the configured total-byte limit"
                    );
                }
                output.write(buffer, 0, read);
            }
        }
        return copied;
    }

    /** 返回安全{@code Add}。 */
    private long safeAdd(long left, long right) throws TemplateMaterializationException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw failure(
                    TemplateMaterializationException.Reason.TOTAL_BYTES_EXCEEDED,
                    "Template byte count overflowed",
                    exception
            );
        }
    }

    private String resolveRelativePath(Resource resource, String templatePrefix) throws IOException {
        String resourceUrl = resource.getURL().toExternalForm();
        int prefixIndex = resourceUrl.indexOf(templatePrefix);
        if (prefixIndex < 0) {
            throw failure(
                    TemplateMaterializationException.Reason.INVALID_RESOURCE_PATH,
                    "Template resource does not belong to the requested template"
            );
        }
        return resourceUrl.substring(prefixIndex + templatePrefix.length());
    }

    /** 判断目录资源是否满足约束。 */
    private boolean isDirectoryResource(Resource resource, String relativePath) {
        if (relativePath.endsWith("/")) {
            return true;
        }
        try {
            return resource.isFile() && resource.getFile().isDirectory();
        } catch (IOException ignored) {
            return false;
        }
    }

    /** 确保安全父级{@code Directories}已达到可用状态。 */
    private void ensureSafeParentDirectories(Path root, Path parent) throws IOException {
        Path relativeParent = root.relativize(parent);
        Path current = root;
        for (Path segment : relativeParent) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(current);
                continue;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    current,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw failure(
                        TemplateMaterializationException.Reason.UNSAFE_TARGET,
                        "Template target contains an unsafe directory entry"
                );
            }
        }
    }

    /** 校验并返回有效的安全{@code Empty}目录。 */
    private Path requireSafeEmptyDirectory(Path targetDirectory) throws IOException {
        Path target = normalizeTarget(targetDirectory);
        BasicFileAttributes attributes = Files.readAttributes(
                target,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw failure(TemplateMaterializationException.Reason.UNSAFE_TARGET, "Template target is not a safe directory");
        }
        try (var entries = Files.newDirectoryStream(target)) {
            if (entries.iterator().hasNext()) {
                throw failure(TemplateMaterializationException.Reason.UNSAFE_TARGET, "Template target directory is not empty");
            }
        }
        return target;
    }

    /** 拒绝{@code Existing}目标并记录原因。 */
    private void rejectExistingTarget(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        BasicFileAttributes attributes = Files.readAttributes(
                target,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw failure(TemplateMaterializationException.Reason.UNSAFE_TARGET, "Template target is unsafe");
        }
        throw failure(TemplateMaterializationException.Reason.TARGET_ALREADY_EXISTS, "Template target already exists");
    }

    /** 校验{@code ate}模板编号是否有效。 */
    private void validateTemplateId(String templateId) throws TemplateMaterializationException {
        try {
            templateCatalog.requireKnown(templateId);
        } catch (IllegalArgumentException exception) {
            throw failure(
                    TemplateMaterializationException.Reason.INVALID_TEMPLATE,
                    "Template id is not present in the packaged catalog",
                    exception
            );
        }
    }

    private Path normalizeTarget(Path targetDirectory) throws TemplateMaterializationException {
        if (targetDirectory == null || targetDirectory.toString().isBlank()) {
            throw failure(TemplateMaterializationException.Reason.UNSAFE_TARGET, "Template target is required");
        }
        return targetDirectory.toAbsolutePath().normalize();
    }

    /** 移动{@code Without}{@code Replace}。 */
    private void moveWithoutReplace(Path source, Path target) throws IOException {
        for (int attempt = 1; attempt <= properties.getPublishMaxAttempts(); attempt++) {
            try {
                moveOnceWithoutReplace(source, target);
                return;
            } catch (AccessDeniedException exception) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    rejectExistingTarget(target);
                }
                if (attempt == properties.getPublishMaxAttempts()) {
                    throw exception;
                }
                awaitPublishRetry(exception);
            }
        }
    }

    /** 移动{@code Once}{@code Without}{@code Replace}。 */
    private void moveOnceWithoutReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    /** 等待{@code Publish}重试完成。 */
    private void awaitPublishRetry(AccessDeniedException publishFailure) throws IOException {
        try {
            Thread.sleep(properties.getPublishRetryDelayMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            IOException interrupted = new IOException("Template publication was interrupted", exception);
            interrupted.addSuppressed(publishFailure);
            throw interrupted;
        }
    }

    private void deleteStagingIfPresent(Path staging) throws IOException {
        if (staging != null && Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            workspaceFileSystemService.deleteDirectory(staging);
        }
    }

    private TemplateMaterializationException failure(TemplateMaterializationException.Reason reason, String message) {
        return new TemplateMaterializationException(reason, message);
    }

    private TemplateMaterializationException failure(TemplateMaterializationException.Reason reason,
                                                     String message,
                                                     Throwable cause) {
        return new TemplateMaterializationException(reason, message, cause);
    }

    @FunctionalInterface
    public interface StagingCustomizer {
        void customize(Path stagingDirectory) throws Exception;
    }

    public record MaterializationResult(Path targetDirectory, int fileCount, long totalBytes) {
    }

    private static final class MutableCopyTotals {
        private int fileCount;
        private long totalBytes;
    }
}
