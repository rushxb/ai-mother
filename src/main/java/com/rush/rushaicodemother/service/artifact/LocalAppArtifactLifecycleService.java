package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 本地文件系统应用产物生命周期实现。 */
@Service
@Slf4j
public class LocalAppArtifactLifecycleService implements AppArtifactLifecycleService {

    private static final String COPY_STAGING_PREFIX = ".artifact-copy-";
    private static final String DEPLOY_STAGING_PREFIX = ".deploy-staging-";
    private static final String DEPLOY_BACKUP_PREFIX = ".deploy-backup-";
    private static final String DELETE_QUARANTINE_PREFIX = ".artifact-delete-";
    private final Path outputRoot;
    private final Path deployRoot;
    private final GeneratedArtifactLifecycleResolver artifactLifecycleResolver;
    private final ArtifactDirectoryCopier artifactDirectoryCopier;
    private final DeploymentKeyPolicy deploymentKeyPolicy;
    private final ArtifactPathMover artifactPathMover;

    /**
     * 创建本地应用制品生命周期服务。
     *
     * @param storageProperties 存储属性
     * @param artifactLifecycleResolver 生成制品生命周期解析器
     * @param deploymentKeyPolicy 部署键策略
     * @param artifactDirectoryCopier 制品目录复制器
     * @param artifactPathMover 制品路径移动器
     */
    public LocalAppArtifactLifecycleService(
            CodeStorageProperties storageProperties,
            GeneratedArtifactLifecycleResolver artifactLifecycleResolver,
            DeploymentKeyPolicy deploymentKeyPolicy,
            ArtifactDirectoryCopier artifactDirectoryCopier,
            ArtifactPathMover artifactPathMover
    ) {
        Objects.requireNonNull(storageProperties, "storageProperties must not be null");
        this.outputRoot = storageProperties.outputRoot();
        this.deployRoot = storageProperties.deployRoot();
        this.artifactLifecycleResolver = Objects.requireNonNull(
                artifactLifecycleResolver,
                "artifactLifecycleResolver must not be null"
        );
        this.artifactDirectoryCopier = Objects.requireNonNull(
                artifactDirectoryCopier,
                "artifactDirectoryCopier must not be null"
        );
        this.deploymentKeyPolicy = Objects.requireNonNull(
                deploymentKeyPolicy,
                "deploymentKeyPolicy must not be null"
        );
        this.artifactPathMover = Objects.requireNonNull(
                artifactPathMover,
                "artifactPathMover must not be null"
        );
    }

    /**
 * 校验并返回有效的{@code Generated}目录。
 *
 * @param app 应用
 * @return 解析后的{@code Generated}目录路径
 */
    @Override
    public Path requireGeneratedDirectory(App app) {
        CodeGenTypeEnum codeGenType = requireCodeGenType(app);
        Path generatedDirectory = artifactLifecycleResolver.resolveCurrent(app.getId(), codeGenType);
        ThrowUtils.throwIf(!Files.isDirectory(generatedDirectory, LinkOption.NOFOLLOW_LINKS),
                ErrorCode.NOT_FOUND_ERROR, "应用代码路径不存在，请先生成应用");
        ThrowUtils.throwIf(Files.isSymbolicLink(generatedDirectory),
                ErrorCode.NO_AUTH_ERROR, "应用生成目录不能是符号链接");
        return toRealPath(generatedDirectory, "应用生成目录解析失败");
    }

    /**
 * 复制{@code Generated}制品。
 *
 * @param sourceApp 来源应用
 * @param targetApp 目标应用
 */
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
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            artifactDirectoryCopier.copy(sourceDirectory, stagingDirectory, ArtifactCopyProfile.GENERATED_SOURCE);
            artifactPathMover.move(stagingDirectory, targetDirectory);
        } catch (BusinessException exception) {
            deleteQuietly(stagingDirectory, "artifact copy staging directory");
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            deleteQuietly(stagingDirectory, "artifact copy staging directory");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Artifact copy was interrupted", exception);
        } catch (ArtifactCopyException exception) {
            deleteQuietly(stagingDirectory, "artifact copy staging directory");
            throw mapArtifactCopyFailure(exception, "Failed to copy application source");
        } catch (Exception exception) {
            deleteQuietly(stagingDirectory, "artifact copy staging directory");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to copy application source", exception);
        }
    }

    /**
 * 准备后续流程所需的部署。
 *
 * @param sourceDirectory 来源目录
 * @param deployKey 部署键
 * @return 部署
 */
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
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            artifactDirectoryCopier.copy(safeSourceDirectory, stagingDirectory, ArtifactCopyProfile.DEPLOYMENT);
            return new LocalDeploymentArtifactTransaction(stagingDirectory, targetDirectory, backupDirectory);
        } catch (BusinessException exception) {
            deleteQuietly(stagingDirectory, "deployment staging directory");
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            deleteQuietly(stagingDirectory, "deployment staging directory");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Deployment artifact copy was interrupted", exception);
        } catch (ArtifactCopyException exception) {
            deleteQuietly(stagingDirectory, "deployment staging directory");
            throw mapArtifactCopyFailure(exception, "Failed to prepare deployment artifact");
        } catch (Exception exception) {
            deleteQuietly(stagingDirectory, "deployment staging directory");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to prepare deployment artifact", exception);
        }
    }

    /**
 * 准备后续流程所需的删除。
 *
 * @param app 应用
 * @return 删除
 */
    @Override
    public AppArtifactDeletionTransaction prepareDeletion(App app) {
        ThrowUtils.throwIf(app == null || app.getId() == null || app.getId() <= 0,
                ErrorCode.PARAMS_ERROR, "应用参数错误");
        String transactionId = UUID.randomUUID().toString();
        List<DeletionTarget> deletionTargets = new ArrayList<>();
        Path outputSafeRoot = requireSafeRoot(outputRoot, "应用生成根目录");

        if (app.getCodeGenType() != null && !app.getCodeGenType().isBlank()) {
            Path generatedDirectory = resolveGeneratedDirectory(app);
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

        // 版本化工作区只依赖应用 ID，即使遗留 codeGenType 缺失也必须清理。
        addManagedGenerationDeletionTargets(
                deletionTargets,
                app.getId(),
                outputSafeRoot,
                transactionId
        );

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

    /**
     * 将版本化发布、发布指针、执行工作区和失败隔离区纳入同一个可回滚删除事务。
     *
     * <p>解析器负责资源归属，文件系统实现再次验证目录必须是安全根下的两级应用目录，
     * 防止未来适配器误把共享根或其他应用目录交给删除事务。</p>
     */
    private void addManagedGenerationDeletionTargets(List<DeletionTarget> deletionTargets,
                                                     Long appId,
                                                     Path outputSafeRoot,
                                                     String transactionId) {
        List<Path> managedRoots = artifactLifecycleResolver.deletionRoots(appId);
        ThrowUtils.throwIf(managedRoots == null, ErrorCode.SYSTEM_ERROR, "生成制品删除目录解析结果为空");
        for (int index = 0; index < managedRoots.size(); index++) {
            Path managedRoot = requireOwnedManagedRoot(managedRoots.get(index), outputSafeRoot, appId);
            Path quarantine = resolveDirectChild(
                    outputSafeRoot,
                    DELETE_QUARANTINE_PREFIX + "managed-" + appId + "-" + index + "-" + transactionId,
                    "托管生成制品隔离位置"
            );
            deletionTargets.add(new DeletionTarget(
                    managedRoot,
                    quarantine,
                    "托管生成制品目录"
            ));
        }
    }

    /** 校验适配器返回的是安全根下精确的 {@code <managed-root>/app-<id>} 目录。 */
    private Path requireOwnedManagedRoot(Path candidate, Path outputSafeRoot, Long appId) {
        ThrowUtils.throwIf(candidate == null, ErrorCode.SYSTEM_ERROR, "托管生成制品目录不能为空");
        Path normalized = candidate.toAbsolutePath().normalize();
        ThrowUtils.throwIf(!normalized.startsWith(outputSafeRoot) || normalized.equals(outputSafeRoot),
                ErrorCode.NO_AUTH_ERROR, "托管生成制品目录越界");
        Path relative = outputSafeRoot.relativize(normalized);
        ThrowUtils.throwIf(relative.getNameCount() != 2
                        || !("app-" + appId).equals(relative.getName(1).toString()),
                ErrorCode.NO_AUTH_ERROR, "托管生成制品目录不属于当前应用");
        Path managedParent = normalized.getParent();
        ThrowUtils.throwIf(managedParent == null || Files.isSymbolicLink(managedParent),
                ErrorCode.NO_AUTH_ERROR, "托管生成制品父目录不安全");
        ThrowUtils.throwIf(Files.exists(managedParent, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(managedParent, LinkOption.NOFOLLOW_LINKS),
                ErrorCode.SYSTEM_ERROR, "托管生成制品父路径不是目录");
        return normalized;
    }

    /**
 * 删除{@code Generated}制品。
 *
 * @param app 应用
 */
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
        CodeGenTypeEnum codeGenType = requireCodeGenType(app);
        Path root = requireSafeRoot(outputRoot, "应用生成根目录");
        return resolveDirectChild(root, codeGenType.getValue() + "_" + app.getId(), "应用生成目录");
    }

    /** 校验应用身份并返回唯一的代码生成类型。 */
    private CodeGenTypeEnum requireCodeGenType(App app) {
        ThrowUtils.throwIf(app == null || app.getId() == null || app.getId() <= 0,
                ErrorCode.PARAMS_ERROR, "应用参数错误");
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(codeGenType == null, ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
        return codeGenType;
    }

    private void validateCompatibleCodeTypes(App sourceApp, App targetApp) {
        CodeGenTypeEnum sourceType = CodeGenTypeEnum.getEnumByValue(sourceApp.getCodeGenType());
        CodeGenTypeEnum targetType = CodeGenTypeEnum.getEnumByValue(targetApp.getCodeGenType());
        ThrowUtils.throwIf(sourceType == null || sourceType != targetType,
                ErrorCode.PARAMS_ERROR, "源应用与目标应用代码类型不一致");
    }

    /** 校验并返回有效的{@code Deployable}来源目录。 */
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
        return realSource;
    }

    /** 校验并返回有效的安全根。 */
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
        ThrowUtils.throwIf(!deploymentKeyPolicy.isValid(deployKey),
                ErrorCode.PARAMS_ERROR, "部署标识格式错误");
    }

    private BusinessException mapArtifactCopyFailure(
            ArtifactCopyException exception,
            String operationMessage
    ) {
        ErrorCode errorCode = switch (exception.reason()) {
            case UNSAFE_SYMBOLIC_LINK -> ErrorCode.NO_AUTH_ERROR;
            case CANCELLED, TIMED_OUT, INTERRUPTED, LIMIT_EXCEEDED, SOURCE_CHANGED ->
                    ErrorCode.OPERATION_ERROR;
            case INCOMPLETE_COPY, INVALID_PATH -> ErrorCode.SYSTEM_ERROR;
        };
        return new BusinessException(errorCode, operationMessage + ": " + exception.getMessage(), exception);
    }

    /** 将当前对象转换为{@code Real}路径。 */
    private Path toRealPath(Path path, String failureMessage) {
        try {
            return path.toRealPath();
        } catch (IOException | SecurityException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, failureMessage, exception);
        }
    }

    /** 删除目录{@code If}{@code Exists}。 */
    private void deleteDirectoryIfExists(Path target, String label) {
        try {
            deleteTree(target);
            log.info("已删除{}: {}", label, target);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "删除" + label + "失败", exception);
        }
    }

    /** 删除{@code Tree}。 */
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

            /**
 * 在目录访问完成后处理异常并收口遍历状态。
 *
 * @param directory 目录
 * @param exception 待转换或处理的异常
 * @return 方法执行结果
 */
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

    /** 删除{@code Quietly}。 */
    private void deleteQuietly(Path target, String label) {
        try {
            deleteTree(target);
        } catch (IOException exception) {
            log.warn("清理{}失败: {}", label, target, LogExceptionSanitizer.sanitize(exception));
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

        /** 处理{@code activate}。 */
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
                    artifactPathMover.move(deletionTarget.activePath(), deletionTarget.quarantinePath());
                    movedTargets.add(deletionTarget);
                }
                activated = true;
            } catch (RuntimeException | IOException activationFailure) {
                restoreAfterActivationFailure(activationFailure);
            }
        }

        /** 处理提交。 */
        @Override
        public synchronized void commit() {
            ThrowUtils.throwIf(!activated, ErrorCode.OPERATION_ERROR, "应用产物删除事务尚未激活");
            if (committed) {
                return;
            }
            RuntimeException firstFailure = null;
            // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
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

        /** 处理回滚。 */
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

        /** 处理恢复执行后{@code Activation}失败。 */
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

        /** 返回恢复{@code Moved}{@code Targets}。 */
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
                    artifactPathMover.move(deletionTarget.quarantinePath(), deletionTarget.activePath());
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

        /** 处理{@code activate}。 */
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
                    artifactPathMover.move(targetDirectory, backupDirectory);
                }
                try {
                    artifactPathMover.move(stagingDirectory, targetDirectory);
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

        /** 处理提交。 */
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

        /** 处理回滚。 */
        @Override
        public synchronized void rollback() {
            if (committed) {
                return;
            }
            try {
                if (activated) {
                    deleteTree(targetDirectory);
                    if (Files.exists(backupDirectory, LinkOption.NOFOLLOW_LINKS)) {
                        artifactPathMover.move(backupDirectory, targetDirectory);
                    }
                    activated = false;
                }
                deleteTree(stagingDirectory);
                deleteTree(backupDirectory);
            } catch (Exception exception) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "回滚部署目录失败", exception);
            }
        }

        /** 处理恢复{@code Backup}执行后{@code Activation}失败。 */
        private void restoreBackupAfterActivationFailure(Exception activationFailure) throws IOException {
            try {
                if (Files.exists(backupDirectory, LinkOption.NOFOLLOW_LINKS)
                        && !Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS)) {
                    artifactPathMover.move(backupDirectory, targetDirectory);
                }
            } catch (Exception restoreFailure) {
                activationFailure.addSuppressed(restoreFailure);
                throw new IOException("切换部署目录失败且旧版本恢复失败", activationFailure);
            }
            throw new IOException("切换部署目录失败", activationFailure);
        }
    }

}
