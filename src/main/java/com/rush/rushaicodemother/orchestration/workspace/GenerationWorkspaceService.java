package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
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
            ".git", ".idea", "node_modules", "node_modle", "node_module", "dist", "target", ".DS_Store",
            ".generation-publication-owner"
    );

    public static final Set<String> EDITABLE_EXTENSIONS = Set.of(
            "html", "css", "js", "ts", "jsx", "tsx", "vue", "json", "md", "txt", "xml", "svg",
            "yml", "yaml", "go", "sql", "mod", "sum"
    );

    private final CodeStorageProperties storageProperties;
    private final GenerationWorkspaceExecutionScope executionScope;
    private final GenerationWorkspacePublicationCatalog publicationCatalog;

    /** Compatibility constructor for non-Spring callers and focused unit tests. */
    public GenerationWorkspaceService(CodeStorageProperties storageProperties) {
        this(storageProperties, new GenerationWorkspaceExecutionScope(),
                new GenerationWorkspacePublicationCatalog(storageProperties));
    }

    public GenerationWorkspaceService(CodeStorageProperties storageProperties,
                                      GenerationWorkspaceExecutionScope executionScope) {
        this(storageProperties, executionScope,
                new GenerationWorkspacePublicationCatalog(storageProperties));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GenerationWorkspaceService(CodeStorageProperties storageProperties,
                                      GenerationWorkspaceExecutionScope executionScope,
                                      GenerationWorkspacePublicationCatalog publicationCatalog) {
        this.storageProperties = Objects.requireNonNull(
                storageProperties,
                "storageProperties must not be null"
        );
        this.executionScope = Objects.requireNonNull(
                executionScope,
                "executionScope must not be null"
        );
        this.publicationCatalog = Objects.requireNonNull(
                publicationCatalog,
                "publicationCatalog must not be null"
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
        GenerationExecutionWorkspace executionWorkspace = executionScope.current(appId, codeGenType).orElse(null);
        if (executionWorkspace != null) {
            return executionWorkspace.workspace();
        }
        return resolveCanonical(appId, codeGenType);
    }

    /** Resolves the user-visible canonical application workspace, bypassing execution scoping. */
    public GenerationWorkspace resolveCanonical(Long appId, CodeGenTypeEnum codeGenType) {
        validateIdentity(appId, codeGenType);
        try {
            WorkspaceLocation location = resolveLocation(appId, codeGenType);
            return createWorkspace(appId, codeGenType, location, null);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成工作区解析失败", exception);
        }
    }

    /** Resolves the exact publication owned by one durable task, rejecting a stale/current mismatch. */
    public GenerationWorkspace resolvePublished(Long appId,
                                                CodeGenTypeEnum codeGenType,
                                                String expectedTaskId) {
        validateIdentity(appId, codeGenType);
        if (expectedTaskId == null || !expectedTaskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "published workspace task identity is invalid");
        }
        try {
            GenerationWorkspacePublicationPointer pointer = publicationCatalog.findCurrent(appId, codeGenType)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.OPERATION_ERROR,
                            "published workspace pointer is unavailable"));
            if (!expectedTaskId.equals(pointer.taskId())) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "published workspace belongs to a different generation task");
            }
            Path outputRoot = canonicalOutputRoot();
            Path publishedWorkspace = publicationCatalog.resolveWorkspace(pointer);
            ensureWithinOutputRoot(publishedWorkspace, outputRoot);
            return createWorkspace(
                    appId,
                    codeGenType,
                    new WorkspaceLocation(
                            declaredWorkspaceRoot(outputRoot, appId, codeGenType),
                            publishedWorkspace),
                    true
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "published workspace resolution failed", exception);
        }
    }

    /** Resolves one exact task/epoch workspace for asynchronous callbacks that cannot use ThreadLocal scope. */
    public GenerationWorkspace resolveExecution(GenerationExecutionFence fence,
                                                Long appId,
                                                CodeGenTypeEnum codeGenType) {
        validateIdentity(appId, codeGenType);
        return executionScope.find(fence, appId, codeGenType)
                .map(GenerationExecutionWorkspace::workspace)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "execution workspace scope is unavailable"
                ));
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
            try {
                GenerationWorkspace workspace = resolveCanonical(appId, codeGenType);
                Path declaredRoot = declaredWorkspaceRoot(outputRoot, appId, codeGenType);
                if (candidate.equals(declaredRoot)
                        || candidate.equals(workspace.canonicalRootPath())) {
                    return workspace;
                }
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
        GenerationExecutionWorkspace executionWorkspace = executionScope.current(appId, codeGenType).orElse(null);
        if (executionWorkspace != null) {
            return executionWorkspace.workspace();
        }
        try {
            if (publicationCatalog.findCurrentWorkspace(appId, codeGenType).isPresent()) {
                return resolveCanonical(appId, codeGenType);
            }
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
            return resolveCanonical(appId, codeGenType);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成工作区创建失败", exception);
        }
    }

    /**
     * Describes a workspace path already created by the execution-workspace materializer.
     * Callers cannot use this method to escape the configured output root or execution subtree.
     */
    public GenerationWorkspace resolveExecutionWorkspace(Long appId,
                                                          CodeGenTypeEnum codeGenType,
                                                          Path workspaceRoot) {
        return resolveExecutionWorkspace(appId, codeGenType, workspaceRoot, null);
    }

    /**
     * Describes an execution directory while preserving whether it was seeded from an existing
     * user project. The directory is created eagerly, so physical existence alone is not enough
     * to decide whether the CREATE pipeline should handle the task.
     */
    public GenerationWorkspace resolveExecutionWorkspace(Long appId,
                                                          CodeGenTypeEnum codeGenType,
                                                          Path workspaceRoot,
                                                          Boolean logicalExists) {
        validateIdentity(appId, codeGenType);
        if (workspaceRoot == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "execution workspace path is required");
        }
        try {
            Path outputRoot = canonicalOutputRoot();
            Path executionRoot = outputRoot.resolve(GenerationExecutionWorkspaceService.EXECUTION_ROOT_NAME)
                    .normalize();
            Path candidate = workspaceRoot.toAbsolutePath().normalize();
            if (!candidate.startsWith(executionRoot)
                    || candidate.equals(executionRoot)
                    || candidate.getFileName() == null
                    || !WORKSPACE_DIRECTORY_NAME.equals(candidate.getFileName().toString())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "execution workspace path is invalid");
            }
            validateDirectory(candidate, "execution workspace path is unsafe");
            Path canonicalRootPath = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            ensureWithinOutputRoot(canonicalRootPath, outputRoot);
            return createWorkspace(
                    appId,
                    codeGenType,
                    new WorkspaceLocation(candidate, canonicalRootPath),
                    logicalExists
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "execution workspace resolution failed", exception);
        }
    }

    private WorkspaceLocation resolveLocation(Long appId, CodeGenTypeEnum codeGenType) throws Exception {
        Path outputRoot = canonicalOutputRoot();
        Path rootPath = declaredWorkspaceRoot(outputRoot, appId, codeGenType);
        Path publishedWorkspace = publicationCatalog.findCurrentWorkspace(appId, codeGenType)
                .orElse(null);
        if (publishedWorkspace != null) {
            ensureWithinOutputRoot(publishedWorkspace, outputRoot);
            return new WorkspaceLocation(rootPath, publishedWorkspace);
        }
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

    private static final String WORKSPACE_DIRECTORY_NAME = "workspace";

    private GenerationWorkspace createWorkspace(
            Long appId,
            CodeGenTypeEnum codeGenType,
            WorkspaceLocation location,
            Boolean logicalExists
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
                logicalExists == null
                        ? Files.isDirectory(canonicalRootPath, LinkOption.NOFOLLOW_LINKS)
                        : logicalExists,
                frontendRootPath.normalize(),
                backendRootPath == null ? null : backendRootPath.normalize(),
                HIDDEN_FILE_NAMES,
                EDITABLE_EXTENSIONS
        );
    }

    private record WorkspaceLocation(Path rootPath, Path canonicalRootPath) {
    }
}
