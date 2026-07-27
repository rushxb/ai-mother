package com.rush.rushaicodemother.orchestration.snapshot;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemException;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 拥有应用程序范围生成快照的规范文件系统边界。
 *
 * <p>Snapshot 使用者必须从此服务获取应用程序和快照目录
 * 从存储常量、应用程序标识符或快照名称重建路径。</p>
 */
@Service
public class GenerationSnapshotWorkspaceService {

    private final CodeStorageProperties storageProperties;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final SnapshotNamePolicy snapshotNamePolicy;

    public GenerationSnapshotWorkspaceService(
            CodeStorageProperties storageProperties,
            WorkspaceFileSystemService workspaceFileSystemService,
            SnapshotNamePolicy snapshotNamePolicy
    ) {
        this.storageProperties = Objects.requireNonNull(storageProperties, "storageProperties must not be null");
        this.workspaceFileSystemService = Objects.requireNonNull(
                workspaceFileSystemService,
                "workspaceFileSystemService must not be null"
        );
        this.snapshotNamePolicy = Objects.requireNonNull(snapshotNamePolicy, "snapshotNamePolicy must not be null");
    }

    /** 解析应用程序快照根而不创建它。 */
    public Path resolveApplicationRoot(Long appId) {
        requireAppId(appId);
        Path storageRoot = storageProperties.snapshotRoot();
        Path applicationRoot = storageRoot.resolve(String.valueOf(appId)).normalize();
        ensureDirectChild(storageRoot, applicationRoot, "Application snapshot root must be a direct child of the snapshot storage root");
        validateExistingDirectory(storageRoot, "Snapshot storage root is unsafe");
        validateExistingDirectory(applicationRoot, "Application snapshot root is unsafe");
        return applicationRoot;
    }

    /** 创建并验证应用程序快照根。 */
    public Path prepareApplicationRoot(Long appId) {
        Path applicationRoot = resolveApplicationRoot(appId);
        try {
            workspaceFileSystemService.ensureDirectory(storageProperties.snapshotRoot());
            return workspaceFileSystemService.ensureDirectory(applicationRoot);
        } catch (IOException exception) {
            throw storageFailure("Failed to prepare the application snapshot root", exception);
        }
    }

    /** 解析一个经过验证的快照目录路径，而不要求它存在。 */
    public Path resolveSnapshot(Long appId, String snapshotName) {
        String normalizedName = snapshotNamePolicy.validateRequired(snapshotName);
        Path applicationRoot = resolveApplicationRoot(appId);
        Path snapshotPath = applicationRoot.resolve(normalizedName).normalize();
        ensureDirectChild(applicationRoot, snapshotPath, "Snapshot path must be a direct child of the application snapshot root");
        validateExistingDirectory(snapshotPath, "Snapshot directory is unsafe");
        return snapshotPath;
    }

    /** 解析一个现有的非符号链接快照目录。 */
    public Path resolveExistingSnapshot(Long appId, String snapshotName) {
        Path applicationRoot = resolveApplicationRoot(appId);
        Path expected = resolveSnapshot(appId, snapshotName);
        try {
            return workspaceFileSystemService.resolveExistingDirectChildDirectory(applicationRoot, expected);
        } catch (IOException exception) {
            throw storageFailure("Failed to resolve the snapshot directory", exception);
        }
    }

    /**
     * 验证工件报告的快照路径正是其应用程序的规范路径，并且
     * 快照名称。仅前缀成员资格是故意不够的。
     */
    public Path resolveReportedSnapshot(Long appId, String snapshotName, String reportedPath) {
        if (reportedPath == null || reportedPath.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Reported snapshot path must not be blank");
        }
        Path expected = resolveSnapshot(appId, snapshotName);
        Path reported;
        try {
            reported = Path.of(reportedPath.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Reported snapshot path is invalid", exception);
        }
        if (!reported.equals(expected)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "Reported snapshot path does not match the canonical snapshot path");
        }
        return expected;
    }

    private void validateExistingDirectory(Path directory, String message) {
        try {
            workspaceFileSystemService.isDirectory(directory);
        } catch (IOException exception) {
            throw storageFailure(message, exception);
        }
    }

    private void ensureDirectChild(Path root, Path child, String message) {
        if (child.equals(root) || child.getParent() == null || !child.getParent().equals(root)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, message);
        }
    }

    private void requireAppId(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Application id must be positive");
        }
    }

    private BusinessException storageFailure(String message, IOException exception) {
        ErrorCode errorCode = exception instanceof WorkspaceFileSystemException workspaceException
                && workspaceException.reason() == WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK
                ? ErrorCode.NO_AUTH_ERROR
                : ErrorCode.SYSTEM_ERROR;
        return new BusinessException(errorCode, message, exception);
    }
}
