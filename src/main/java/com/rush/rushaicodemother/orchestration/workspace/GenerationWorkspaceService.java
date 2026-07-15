package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Service;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves and prepares the canonical filesystem layout for generated applications.
 *
 * <p>All generation modules must use this service instead of rebuilding output paths with string
 * concatenation. Resolved workspaces are normalized, constrained to the configured output root and
 * rejected when the application directory is a symbolic link or a non-directory entry.</p>
 */
@Service
public class GenerationWorkspaceService {

    public static final Set<String> HIDDEN_FILE_NAMES = Set.of(
            ".git", ".idea", "node_modules", "node_modle", "node_module", "dist", "target", ".DS_Store"
    );

    public static final Set<String> EDITABLE_EXTENSIONS = Set.of(
            "html", "css", "js", "ts", "jsx", "tsx", "vue", "json", "md", "txt", "xml", "svg",
            "yml", "yaml", "go", "sql", "mod", "sum"
    );

    private final CodeStorageProperties storageProperties;

    public GenerationWorkspaceService(CodeStorageProperties storageProperties) {
        this.storageProperties = Objects.requireNonNull(
                storageProperties,
                "storageProperties must not be null"
        );
    }

    public GenerationWorkspace resolve(App app, CodeGenTypeEnum codeGenType) {
        if (app == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成工作区参数错误");
        }
        return resolve(app.getId(), codeGenType);
    }

    /** Resolves a workspace without forcing orchestration code to construct a persistence entity. */
    public GenerationWorkspace resolve(Long appId, CodeGenTypeEnum codeGenType) {
        validateIdentity(appId, codeGenType);
        try {
            WorkspaceLocation location = resolveLocation(appId, codeGenType);
            return createWorkspace(appId, codeGenType, location);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成工作区解析失败", exception);
        }
    }

    /**
     * Resolves an artifact-reported application workspace only when it exactly matches one canonical
     * workspace layout for the supplied application.
     */
    public GenerationWorkspace resolveReportedWorkspace(Long appId, Path reportedPath) {
        if (appId == null || appId <= 0 || reportedPath == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Application id and reported workspace path are required");
        }
        Path candidate = reportedPath.toAbsolutePath().normalize();
        Path outputRoot;
        try {
            outputRoot = canonicalOutputRoot();
        } catch (Exception exception) {
            throw new ReportedWorkspaceResolutionException(
                    ReportedWorkspaceResolutionException.Reason.WORKSPACE_UNAVAILABLE,
                    "Canonical output root is unavailable",
                    exception
            );
        }
        for (CodeGenTypeEnum codeGenType : CodeGenTypeEnum.values()) {
            if (!candidate.equals(declaredWorkspaceRoot(outputRoot, appId, codeGenType))) {
                continue;
            }
            try {
                return resolve(appId, codeGenType);
            } catch (BusinessException exception) {
                ReportedWorkspaceResolutionException.Reason reason =
                        exception.getCode() == ErrorCode.NO_AUTH_ERROR.getCode()
                                ? ReportedWorkspaceResolutionException.Reason.UNSAFE_WORKSPACE
                                : ReportedWorkspaceResolutionException.Reason.WORKSPACE_UNAVAILABLE;
                throw new ReportedWorkspaceResolutionException(reason, exception.getMessage(), exception);
            }
        }
        throw new ReportedWorkspaceResolutionException(
                ReportedWorkspaceResolutionException.Reason.CONTEXT_MISMATCH,
                "Reported workspace path does not match the application context"
        );
    }

    /**
     * Creates the canonical application directory when absent and returns the validated workspace.
     *
     * <p>Creation is limited to one direct child below the configured output root. Concurrent creators
     * are supported by re-validating an entry that appeared between the existence check and create call.</p>
     */
    public GenerationWorkspace prepare(Long appId, CodeGenTypeEnum codeGenType) {
        validateIdentity(appId, codeGenType);
        try {
            Path outputRoot = canonicalOutputRoot();
            Files.createDirectories(outputRoot);
            validateDirectory(outputRoot, "生成代码根目录无效");

            Path workspaceRoot = declaredWorkspaceRoot(outputRoot, appId, codeGenType);
            if (!Files.exists(workspaceRoot, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(workspaceRoot);
                } catch (FileAlreadyExistsException ignored) {
                    // Another request created the same workspace; validation below decides whether it is safe.
                }
            }
            validateDirectory(workspaceRoot, "生成工作区路径不是安全目录");
            return resolve(appId, codeGenType);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成工作区创建失败", exception);
        }
    }

    private WorkspaceLocation resolveLocation(Long appId, CodeGenTypeEnum codeGenType) throws Exception {
        Path outputRoot = canonicalOutputRoot();
        Path rootPath = declaredWorkspaceRoot(outputRoot, appId, codeGenType);
        if (Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            validateDirectory(rootPath, "生成工作区路径不是安全目录");
        }
        Path canonicalRootPath = Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)
                ? rootPath.toRealPath(LinkOption.NOFOLLOW_LINKS)
                : rootPath.toAbsolutePath().normalize();
        ensureWithinOutputRoot(canonicalRootPath, outputRoot);
        return new WorkspaceLocation(rootPath, canonicalRootPath);
    }

    private Path canonicalOutputRoot() throws Exception {
        return storageProperties.outputRoot().toFile().getCanonicalFile().toPath();
    }

    private Path declaredWorkspaceRoot(
            Path outputRoot,
            Long appId,
            CodeGenTypeEnum codeGenType
    ) {
        Path workspaceRoot = outputRoot.resolve(codeGenType.getValue() + "_" + appId).normalize();
        ensureWithinOutputRoot(workspaceRoot, outputRoot);
        return workspaceRoot;
    }

    private void validateDirectory(Path directory, String errorMessage) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(
                directory,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, errorMessage);
        }
    }

    private void ensureWithinOutputRoot(Path candidate, Path outputRoot) {
        if (!candidate.startsWith(outputRoot)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "非法生成工作区路径");
        }
    }

    private void validateIdentity(Long appId, CodeGenTypeEnum codeGenType) {
        if (appId == null || appId <= 0 || codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成工作区参数错误");
        }
    }

    private GenerationWorkspace createWorkspace(
            Long appId,
            CodeGenTypeEnum codeGenType,
            WorkspaceLocation location
    ) {
        Path canonicalRootPath = location.canonicalRootPath();
        Path frontendRootPath = codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT
                ? canonicalRootPath.resolve("frontend")
                : canonicalRootPath;
        Path backendRootPath = switch (codeGenType) {
            case FULL_STACK_PROJECT -> canonicalRootPath.resolve("backend");
            case BACKEND_PROJECT -> canonicalRootPath;
            default -> null;
        };
        return new GenerationWorkspace(
                appId,
                codeGenType,
                location.rootPath(),
                canonicalRootPath,
                Files.isDirectory(canonicalRootPath, LinkOption.NOFOLLOW_LINKS),
                frontendRootPath.normalize(),
                backendRootPath == null ? null : backendRootPath.normalize(),
                HIDDEN_FILE_NAMES,
                EDITABLE_EXTENSIONS
        );
    }

    private record WorkspaceLocation(Path rootPath, Path canonicalRootPath) {
    }
}
