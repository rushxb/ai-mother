package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** 本地文件系统应用产物生命周期实现。 */
@Service
@Slf4j
public class LocalAppArtifactLifecycleService implements AppArtifactLifecycleService {

    private static final Pattern DEPLOY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9]{6,64}");
    private static final String COPY_STAGING_PREFIX = ".artifact-copy-";
    private static final String DEPLOY_STAGING_PREFIX = ".deploy-staging-";
    private static final String DEPLOY_BACKUP_PREFIX = ".deploy-backup-";
    private static final String DELETE_QUARANTINE_PREFIX = ".artifact-delete-";
    private static final List<String> GENERATED_DIRECTORY_EXCLUSIONS = List.of(
            ".git", ".idea", "node_modules", "dist", "target"
    );
    private static final List<String> GENERATED_FILE_EXCLUSIONS = List.of(
            ".ai-code-install.stamp",
            ".ai-code-critical.stamp",
            ".ai-code-presentation.stamp"
    );

    private final Path outputRoot;
    private final Path deployRoot;
    private final boolean windows;
    private final RobocopyDirectoryCopier robocopyDirectoryCopier;

    @Autowired
    public LocalAppArtifactLifecycleService(RobocopyDirectoryCopier robocopyDirectoryCopier) {
        this(
                Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR),
                Path.of(AppConstant.CODE_DEPLOY_ROOT_DIR),
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows"),
                robocopyDirectoryCopier
        );
    }

    LocalAppArtifactLifecycleService(
            Path outputRoot,
            Path deployRoot,
            boolean windows,
            RobocopyDirectoryCopier robocopyDirectoryCopier
    ) {
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
        this.deployRoot = deployRoot.toAbsolutePath().normalize();
        this.windows = windows;
        this.robocopyDirectoryCopier = robocopyDirectoryCopier;
    }

    @Override
    public Path requireGeneratedDirectory(App app) {
        Path generatedDirectory = resolveGeneratedDirectory(app);
        ThrowUtils.throwIf(!Files.isDirectory(generatedDirectory, LinkOption.NOFOLLOW_LINKS),
                ErrorCode.NOT_FOUND_ERROR, "应用代码路径不存在，请先生成应用");
        ThrowUtils.throwIf(Files.isSymbolicLink(generatedDirectory),
                ErrorCode.NO_AUTH_ERROR, "应用生成目录不能是符号链接");
        return toRealPath(generatedDirectory, "应用生成目录解析失败");
    }

    @Override
    public void copyGeneratedArtifact(App sourceApp, App targetApp) {
        Path sourceDirectory = requireGeneratedDirectory(sourceApp);
        Path targetDirectory = resolveGeneratedDirectory(targetApp);
        validateCompatibleCodeTypes(sourceApp, targetApp);
        ThrowUtils.throwIf(Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS),
                ErrorCode.OPERATION_ERROR, "复制应用失败，目标代码目录已存在");

        Path root = requireSafeRoot(outputRoot, "应用生成根目录");
        Path stagingDirectory = resolveDirectChild(
                root,
                COPY_STAGING_PREFIX + targetApp.getId() + "-" + UUID.randomUUID(),
                "复制暂存目录"
        );
        try {
            copyDirectory(sourceDirectory, stagingDirectory, CopyProfile.GENERATED_SOURCE);
            movePath(stagingDirectory, targetDirectory);
        } catch (BusinessException exception) {
            deleteQuietly(stagingDirectory, "复制暂存目录");
            throw exception;
        } catch (Exception exception) {
            deleteQuietly(stagingDirectory, "复制暂存目录");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "复制应用代码失败", exception);
        }
    }

    @Override
    public DeploymentArtifactTransaction prepareDeployment(Path sourceDirectory, String deployKey) {
        validateDeployKey(deployKey);
        Path safeSourceDirectory = requireDeployableSourceDirectory(sourceDirectory);
        Path root = requireSafeRoot(deployRoot, "应用部署根目录");
        String transactionId = UUID.randomUUID().toString();
        Path stagingDirectory = resolveDirectChild(
                root,
                DEPLOY_STAGING_PREFIX + deployKey + "-" + transactionId,
                "部署暂存目录"
        );
        Path targetDirectory = resolveDirectChild(root, deployKey, "部署目录");
        Path backupDirectory = resolveDirectChild(
                root,
                DEPLOY_BACKUP_PREFIX + deployKey + "-" + transactionId,
                "部署备份目录"
        );
        try {
            copyDirectory(safeSourceDirectory, stagingDirectory, CopyProfile.DEPLOYMENT);
            return new LocalDeploymentArtifactTransaction(stagingDirectory, targetDirectory, backupDirectory);
        } catch (BusinessException exception) {
            deleteQuietly(stagingDirectory, "部署暂存目录");
            throw exception;
        } catch (Exception exception) {
            deleteQuietly(stagingDirectory, "部署暂存目录");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "准备部署产物失败", exception);
        }
    }

    @Override
    public AppArtifactDeletionTransaction prepareDeletion(App app) {
        ThrowUtils.throwIf(app == null || app.getId() == null || app.getId() <= 0,
                ErrorCode.PARAMS_ERROR, "应用参数错误");
        String transactionId = UUID.randomUUID().toString();
        List<DeletionTarget> deletionTargets = new ArrayList<>();

        if (app.getCodeGenType() != null && !app.getCodeGenType().isBlank()) {
            Path generatedDirectory = resolveGeneratedDirectory(app);
            Path outputSafeRoot = requireSafeRoot(outputRoot, "应用生成根目录");
            Path generatedQuarantine = resolveDirectChild(
                    outputSafeRoot,
                    DELETE_QUARANTINE_PREFIX + "generated-" + app.getId() + "-" + transactionId,
                    "应用生成目录隔离位置"
            );
            deletionTargets.add(new DeletionTarget(
                    generatedDirectory,
                    generatedQuarantine,
                    "应用生成目录"
            ));
        }

        if (app.getDeployKey() != null && !app.getDeployKey().isBlank()) {
            validateDeployKey(app.getDeployKey());
            Path deploySafeRoot = requireSafeRoot(deployRoot, "应用部署根目录");
            Path deploymentDirectory = resolveDirectChild(
                    deploySafeRoot,
                    app.getDeployKey(),
                    "应用部署目录"
            );
            Path deploymentQuarantine = resolveDirectChild(
                    deploySafeRoot,
                    DELETE_QUARANTINE_PREFIX + "deployment-" + app.getId() + "-" + transactionId,
                    "应用部署目录隔离位置"
            );
            deletionTargets.add(new DeletionTarget(
                    deploymentDirectory,
                    deploymentQuarantine,
                    "应用部署目录"
            ));
        }

        return new LocalAppArtifactDeletionTransaction(deletionTargets);
    }

    @Override
    public void deleteGeneratedArtifact(App app) {
        if (app == null || app.getId() == null || app.getId() <= 0 || app.getCodeGenType() == null
                || app.getCodeGenType().isBlank()) {
            return;
        }
        Path generatedDirectory = resolveGeneratedDirectory(app);
        deleteDirectoryIfExists(generatedDirectory, "应用生成目录");
    }

    private Path resolveGeneratedDirectory(App app) {
        ThrowUtils.throwIf(app == null || app.getId() == null || app.getId() <= 0,
                ErrorCode.PARAMS_ERROR, "应用参数错误");
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(codeGenType == null, ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
        Path root = requireSafeRoot(outputRoot, "应用生成根目录");
        return resolveDirectChild(root, codeGenType.getValue() + "_" + app.getId(), "应用生成目录");
    }

    private void validateCompatibleCodeTypes(App sourceApp, App targetApp) {
        CodeGenTypeEnum sourceType = CodeGenTypeEnum.getEnumByValue(sourceApp.getCodeGenType());
        CodeGenTypeEnum targetType = CodeGenTypeEnum.getEnumByValue(targetApp.getCodeGenType());
        ThrowUtils.throwIf(sourceType == null || sourceType != targetType,
                ErrorCode.PARAMS_ERROR, "源应用与目标应用代码类型不一致");
    }

    private Path requireDeployableSourceDirectory(Path sourceDirectory) {
        ThrowUtils.throwIf(sourceDirectory == null, ErrorCode.PARAMS_ERROR, "部署源目录不能为空");
        Path root = requireSafeRoot(outputRoot, "应用生成根目录");
        Path normalizedSource = sourceDirectory.toAbsolutePath().normalize();
        ThrowUtils.throwIf(!normalizedSource.startsWith(root), ErrorCode.NO_AUTH_ERROR, "部署源目录越界");
        ThrowUtils.throwIf(Files.isSymbolicLink(normalizedSource),
                ErrorCode.NO_AUTH_ERROR, "部署源目录不能是符号链接");
        ThrowUtils.throwIf(!Files.isDirectory(normalizedSource, LinkOption.NOFOLLOW_LINKS),
                ErrorCode.NOT_FOUND_ERROR, "部署源目录不存在");
        Path realSource = toRealPath(normalizedSource, "部署源目录解析失败");
        ThrowUtils.throwIf(!realSource.startsWith(root), ErrorCode.NO_AUTH_ERROR, "部署源目录越界");
        validateInternalSymbolicLinks(realSource, CopyProfile.DEPLOYMENT);
        return realSource;
    }

    private Path requireSafeRoot(Path configuredRoot, String label) {
        try {
            if (Files.exists(configuredRoot, LinkOption.NOFOLLOW_LINKS)) {
                ThrowUtils.throwIf(Files.isSymbolicLink(configuredRoot),
                        ErrorCode.NO_AUTH_ERROR, label + "不能是符号链接");
                ThrowUtils.throwIf(!Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS),
                        ErrorCode.SYSTEM_ERROR, label + "不是目录");
            } else {
                Files.createDirectories(configuredRoot);
            }
            return configuredRoot.toRealPath();
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | SecurityException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, label + "不可用", exception);
        }
    }

    private Path resolveDirectChild(Path root, String childName, String label) {
        ThrowUtils.throwIf(childName == null || childName.isBlank(), ErrorCode.PARAMS_ERROR, label + "名称不能为空");
        Path target = root.resolve(childName).normalize();
        ThrowUtils.throwIf(!target.startsWith(root) || target.getParent() == null || !target.getParent().equals(root),
                ErrorCode.NO_AUTH_ERROR, label + "路径越界");
        return target;
    }

    private void validateDeployKey(String deployKey) {
        ThrowUtils.throwIf(deployKey == null || !DEPLOY_KEY_PATTERN.matcher(deployKey).matches(),
                ErrorCode.PARAMS_ERROR, "部署标识格式错误");
    }

    private void copyDirectory(Path sourceRoot, Path targetRoot, CopyProfile copyProfile)
            throws IOException, InterruptedException {
        validateInternalSymbolicLinks(sourceRoot, copyProfile);
        if (windows) {
            copyDirectoryWithRobocopy(sourceRoot, targetRoot, copyProfile);
            return;
        }
        copyDirectoryWithNio(sourceRoot, targetRoot, copyProfile);
    }

    private void copyDirectoryWithRobocopy(Path sourceRoot, Path targetRoot, CopyProfile copyProfile)
            throws IOException, InterruptedException {
        Files.createDirectories(targetRoot);
        List<String> excludedDirectories = copyProfile == CopyProfile.GENERATED_SOURCE
                ? GENERATED_DIRECTORY_EXCLUSIONS
                : List.of();
        List<String> excludedFiles = copyProfile == CopyProfile.GENERATED_SOURCE
                ? GENERATED_FILE_EXCLUSIONS
                : List.of();
        robocopyDirectoryCopier.copy(
                sourceRoot,
                targetRoot,
                excludedDirectories,
                excludedFiles
        );
    }

    private void copyDirectoryWithNio(Path sourceRoot, Path targetRoot, CopyProfile copyProfile) throws IOException {
        Files.createDirectories(targetRoot);
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                if (!directory.equals(sourceRoot) && copyProfile.excludesDirectory(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path relativePath = sourceRoot.relativize(directory);
                Path targetDirectory = targetRoot.resolve(relativePath);
                Files.createDirectories(targetDirectory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (copyProfile.excludesFile(file.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }
                Path targetFile = targetRoot.resolve(sourceRoot.relativize(file));
                Files.createDirectories(targetFile.getParent());
                if (Files.isSymbolicLink(file)) {
                    Files.createSymbolicLink(targetFile, Files.readSymbolicLink(file));
                } else {
                    Files.copy(file, targetFile,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void validateInternalSymbolicLinks(Path sourceRoot, CopyProfile copyProfile) {
        try {
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(sourceRoot) && copyProfile.excludesDirectory(directory.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (copyProfile.excludesFile(file.getFileName().toString()) || !Files.isSymbolicLink(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path linkTarget = Files.readSymbolicLink(file);
                    if (linkTarget.isAbsolute()) {
                        throw new UnsafeSymbolicLinkException(file);
                    }
                    Path resolvedTarget = file.getParent().resolve(linkTarget).normalize();
                    if (!resolvedTarget.startsWith(sourceRoot)) {
                        throw new UnsafeSymbolicLinkException(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (UnsafeSymbolicLinkException exception) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,
                    "应用产物包含指向工作区外部的符号链接: " + sourceRoot.relativize(exception.linkPath));
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用产物符号链接校验失败", exception);
        }
    }

    private Path toRealPath(Path path, String failureMessage) {
        try {
            return path.toRealPath();
        } catch (IOException | SecurityException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, failureMessage, exception);
        }
    }

    private void deleteDirectoryIfExists(Path target, String label) {
        try {
            deleteTree(target);
            log.info("已删除{}: {}", label, target);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "删除" + label + "失败", exception);
        }
    }

    private void deleteTree(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(target);
            return;
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
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
    }

    private void deleteQuietly(Path target, String label) {
        try {
            deleteTree(target);
        } catch (IOException exception) {
            log.warn("清理{}失败: {}", label, target, exception);
        }
    }

    private void movePath(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private final class LocalAppArtifactDeletionTransaction implements AppArtifactDeletionTransaction {

        private final List<DeletionTarget> deletionTargets;
        private final List<DeletionTarget> movedTargets = new ArrayList<>();
        private boolean activated;
        private boolean committed;

        private LocalAppArtifactDeletionTransaction(List<DeletionTarget> deletionTargets) {
            this.deletionTargets = List.copyOf(deletionTargets);
        }

        @Override
        public synchronized void activate() {
            ThrowUtils.throwIf(committed, ErrorCode.OPERATION_ERROR, "应用产物删除事务已提交");
            if (activated) {
                return;
            }
            try {
                for (DeletionTarget deletionTarget : deletionTargets) {
                    if (!Files.exists(deletionTarget.activePath(), LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    ThrowUtils.throwIf(Files.isSymbolicLink(deletionTarget.activePath()),
                            ErrorCode.NO_AUTH_ERROR, deletionTarget.label() + "不能是符号链接");
                    ThrowUtils.throwIf(Files.exists(deletionTarget.quarantinePath(), LinkOption.NOFOLLOW_LINKS),
                            ErrorCode.SYSTEM_ERROR, deletionTarget.label() + "隔离位置已存在");
                    movePath(deletionTarget.activePath(), deletionTarget.quarantinePath());
                    movedTargets.add(deletionTarget);
                }
                activated = true;
            } catch (RuntimeException | IOException activationFailure) {
                restoreAfterActivationFailure(activationFailure);
            }
        }

        @Override
        public synchronized void commit() {
            ThrowUtils.throwIf(!activated, ErrorCode.OPERATION_ERROR, "应用产物删除事务尚未激活");
            if (committed) {
                return;
            }
            RuntimeException firstFailure = null;
            for (DeletionTarget deletionTarget : movedTargets) {
                try {
                    deleteTree(deletionTarget.quarantinePath());
                } catch (RuntimeException | IOException exception) {
                    BusinessException cleanupFailure = exception instanceof BusinessException businessException
                            ? businessException
                            : new BusinessException(
                                    ErrorCode.SYSTEM_ERROR,
                                    "清理" + deletionTarget.label() + "隔离目录失败",
                                    exception
                            );
                    if (firstFailure == null) {
                        firstFailure = cleanupFailure;
                    } else {
                        firstFailure.addSuppressed(cleanupFailure);
                    }
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
            committed = true;
            movedTargets.clear();
        }

        @Override
        public synchronized void rollback() {
            if (committed) {
                return;
            }
            RuntimeException firstFailure = restoreMovedTargets();
            if (firstFailure != null) {
                throw firstFailure;
            }
            activated = false;
            movedTargets.clear();
        }

        private void restoreAfterActivationFailure(Exception activationFailure) {
            RuntimeException restoreFailure = restoreMovedTargets();
            movedTargets.clear();
            if (restoreFailure != null) {
                BusinessException consistencyFailure = new BusinessException(
                        ErrorCode.SYSTEM_ERROR,
                        "隔离应用产物失败且已移动目录恢复失败",
                        activationFailure
                );
                consistencyFailure.addSuppressed(restoreFailure);
                throw consistencyFailure;
            }
            if (activationFailure instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "隔离应用产物失败", activationFailure);
        }

        private RuntimeException restoreMovedTargets() {
            RuntimeException firstFailure = null;
            for (int index = movedTargets.size() - 1; index >= 0; index--) {
                DeletionTarget deletionTarget = movedTargets.get(index);
                try {
                    if (!Files.exists(deletionTarget.quarantinePath(), LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    if (Files.exists(deletionTarget.activePath(), LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException(deletionTarget.label() + "恢复目标已被重新创建");
                    }
                    movePath(deletionTarget.quarantinePath(), deletionTarget.activePath());
                } catch (RuntimeException | IOException exception) {
                    BusinessException restoreFailure = exception instanceof BusinessException businessException
                            ? businessException
                            : new BusinessException(
                                    ErrorCode.SYSTEM_ERROR,
                                    "恢复" + deletionTarget.label() + "失败",
                                    exception
                            );
                    if (firstFailure == null) {
                        firstFailure = restoreFailure;
                    } else {
                        firstFailure.addSuppressed(restoreFailure);
                    }
                }
            }
            return firstFailure;
        }
    }

    private record DeletionTarget(Path activePath, Path quarantinePath, String label) {
    }

    private final class LocalDeploymentArtifactTransaction implements DeploymentArtifactTransaction {

        private final Path stagingDirectory;
        private final Path targetDirectory;
        private final Path backupDirectory;
        private boolean activated;
        private boolean committed;

        private LocalDeploymentArtifactTransaction(Path stagingDirectory, Path targetDirectory, Path backupDirectory) {
            this.stagingDirectory = stagingDirectory;
            this.targetDirectory = targetDirectory;
            this.backupDirectory = backupDirectory;
        }

        @Override
        public synchronized void activate() {
            ThrowUtils.throwIf(committed, ErrorCode.OPERATION_ERROR, "部署目录事务已提交");
            if (activated) {
                return;
            }
            ThrowUtils.throwIf(!Files.isDirectory(stagingDirectory, LinkOption.NOFOLLOW_LINKS),
                    ErrorCode.SYSTEM_ERROR, "部署暂存目录不存在");
            ThrowUtils.throwIf(Files.isSymbolicLink(targetDirectory),
                    ErrorCode.NO_AUTH_ERROR, "部署目录不能是符号链接");
            try {
                if (Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS)) {
                    movePath(targetDirectory, backupDirectory);
                }
                try {
                    movePath(stagingDirectory, targetDirectory);
                    activated = true;
                } catch (Exception activationFailure) {
                    restoreBackupAfterActivationFailure(activationFailure);
                }
            } catch (BusinessException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "切换部署目录失败", exception);
            }
        }

        @Override
        public synchronized void commit() {
            ThrowUtils.throwIf(!activated, ErrorCode.OPERATION_ERROR, "部署目录事务尚未激活");
            if (committed) {
                return;
            }
            committed = true;
            deleteQuietly(backupDirectory, "旧部署备份目录");
            deleteQuietly(stagingDirectory, "部署暂存目录");
        }

        @Override
        public synchronized void rollback() {
            if (committed) {
                return;
            }
            try {
                if (activated) {
                    deleteTree(targetDirectory);
                    if (Files.exists(backupDirectory, LinkOption.NOFOLLOW_LINKS)) {
                        movePath(backupDirectory, targetDirectory);
                    }
                    activated = false;
                }
                deleteTree(stagingDirectory);
                deleteTree(backupDirectory);
            } catch (Exception exception) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "回滚部署目录失败", exception);
            }
        }

        private void restoreBackupAfterActivationFailure(Exception activationFailure) throws IOException {
            try {
                if (Files.exists(backupDirectory, LinkOption.NOFOLLOW_LINKS)
                        && !Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS)) {
                    movePath(backupDirectory, targetDirectory);
                }
            } catch (Exception restoreFailure) {
                activationFailure.addSuppressed(restoreFailure);
                throw new IOException("切换部署目录失败且旧版本恢复失败", activationFailure);
            }
            throw new IOException("切换部署目录失败", activationFailure);
        }
    }

    private enum CopyProfile {
        GENERATED_SOURCE,
        DEPLOYMENT;

        private boolean excludesDirectory(String directoryName) {
            return this == GENERATED_SOURCE
                    && GENERATED_DIRECTORY_EXCLUSIONS.contains(directoryName.toLowerCase(Locale.ROOT));
        }

        private boolean excludesFile(String fileName) {
            return this == GENERATED_SOURCE
                    && GENERATED_FILE_EXCLUSIONS.contains(fileName.toLowerCase(Locale.ROOT));
        }
    }

    private static final class UnsafeSymbolicLinkException extends IOException {

        private final Path linkPath;

        private UnsafeSymbolicLinkException(Path linkPath) {
            this.linkPath = linkPath;
        }
    }
}
