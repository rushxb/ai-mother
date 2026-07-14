package com.rush.rushaicodemother.service.screenshot;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.config.CodeDeploymentProperties;
import com.rush.rushaicodemother.config.ScreenshotProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.service.storage.ObjectStorageService;
import com.rush.rushaicodemother.service.storage.ObjectStorageUpload;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/** 默认部署页面截图服务。 */
@Slf4j
public final class DefaultScreenshotService implements ScreenshotService {

    private static final DateTimeFormatter OBJECT_DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final WebPageScreenshotRenderer screenshotRenderer;
    private final ObjectStorageService objectStorageService;
    private final ScreenshotProperties screenshotProperties;
    private final URI deploymentRoot;
    private final Clock clock;
    private final Supplier<String> fileIdSupplier;

    public DefaultScreenshotService(WebPageScreenshotRenderer screenshotRenderer,
                                    ObjectStorageService objectStorageService,
                                    ScreenshotProperties screenshotProperties,
                                    CodeDeploymentProperties deploymentProperties) {
        this(
                screenshotRenderer,
                objectStorageService,
                screenshotProperties,
                deploymentProperties,
                Clock.systemDefaultZone(),
                () -> UUID.randomUUID().toString()
        );
    }

    DefaultScreenshotService(WebPageScreenshotRenderer screenshotRenderer,
                             ObjectStorageService objectStorageService,
                             ScreenshotProperties screenshotProperties,
                             CodeDeploymentProperties deploymentProperties,
                             Clock clock,
                             Supplier<String> fileIdSupplier) {
        this.screenshotRenderer = screenshotRenderer;
        this.objectStorageService = objectStorageService;
        this.screenshotProperties = screenshotProperties;
        this.deploymentRoot = parseDeploymentRoot(deploymentProperties);
        this.clock = clock;
        this.fileIdSupplier = fileIdSupplier;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String generateAndUploadScreenshot(String webUrl) {
        URI targetUri = requireTrustedDeploymentUri(webUrl);
        Path workspace = createWorkspace();
        try {
            log.info("开始生成部署页面截图，target={}", targetUri);
            Path screenshotFile = requireWorkspaceScreenshot(
                    screenshotRenderer.render(targetUri, workspace),
                    workspace
            );
            String objectKey = buildObjectKey();
            String publicUrl = objectStorageService.upload(
                    new ObjectStorageUpload(objectKey, screenshotFile)
            );
            if (publicUrl == null || publicUrl.isBlank()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "上传截图到对象存储失败");
            }
            log.info("部署页面截图上传成功，objectKey={}", objectKey);
            return publicUrl;
        } finally {
            deleteWorkspace(workspace);
        }
    }

    private URI requireTrustedDeploymentUri(String webUrl) {
        if (webUrl == null || webUrl.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "截图的网址不能为空");
        }
        try {
            URI target = URI.create(webUrl.strip());
            if (!target.isAbsolute()
                    || target.getHost() == null
                    || target.getUserInfo() != null
                    || target.getQuery() != null
                    || target.getFragment() != null
                    || !isHttpScheme(target.getScheme())
                    || !sameOrigin(deploymentRoot, target)
                    || containsEncodedPathSeparatorOrTraversal(target)
                    || !target.normalize().equals(target)
                    || !isPathUnderDeploymentRoot(target.getPath())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "截图地址不属于受信任的应用部署目录");
            }
            return target;
        } catch (BusinessException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "截图的网址格式不正确", exception);
        }
    }

    private boolean isPathUnderDeploymentRoot(String targetPath) {
        String basePath = deploymentRoot.getPath();
        if (basePath == null || basePath.isEmpty()) {
            basePath = "/";
        }
        if (!basePath.endsWith("/")) {
            basePath += "/";
        }
        return targetPath != null && targetPath.startsWith(basePath) && targetPath.length() > basePath.length();
    }

    private boolean containsEncodedPathSeparatorOrTraversal(URI target) {
        String rawPath = target.getRawPath();
        if (rawPath == null || rawPath.indexOf('\\') >= 0) {
            return true;
        }
        String normalizedRawPath = rawPath.toLowerCase(Locale.ROOT);
        return normalizedRawPath.contains("%2e")
                || normalizedRawPath.contains("%2f")
                || normalizedRawPath.contains("%5c");
    }

    private Path createWorkspace() {
        Path root = screenshotProperties.getWorkDirectory().toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "截图工作目录不安全或不可用");
            }
            return Files.createDirectory(root.resolve(UUID.randomUUID().toString()));
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建截图工作目录失败", exception);
        }
    }

    private Path requireWorkspaceScreenshot(Path screenshotFile, Path workspace) {
        if (screenshotFile == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成网页截图失败");
        }
        Path normalizedFile = screenshotFile.toAbsolutePath().normalize();
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(normalizedWorkspace)
                || Files.isSymbolicLink(normalizedFile)
                || !Files.isRegularFile(normalizedFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "截图渲染结果不安全或不存在");
        }
        return normalizedFile;
    }

    private String buildObjectKey() {
        String datePath = LocalDate.now(clock).format(OBJECT_DATE_PATH);
        String fileId = fileIdSupplier.get();
        if (fileId == null || !fileId.matches("[A-Za-z0-9-]{8,64}")) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成截图文件标识失败");
        }
        return "screenshots/" + datePath + "/" + fileId + ".jpg";
    }

    private void deleteWorkspace(Path workspace) {
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException exception) {
            log.error("清理截图工作目录失败，workspace={}", workspace, LogExceptionSanitizer.sanitize(exception));
        }
    }

    private static URI parseDeploymentRoot(CodeDeploymentProperties deploymentProperties) {
        if (deploymentProperties == null
                || deploymentProperties.getDeployHost() == null
                || deploymentProperties.getDeployHost().isBlank()) {
            throw new IllegalArgumentException("应用部署根地址不能为空");
        }
        return URI.create(deploymentProperties.getDeployHost().trim()).normalize();
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean isHttpScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }
}
