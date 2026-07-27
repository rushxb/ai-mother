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
 * 为生成的应用程序解析并准备规范的文件系统布局。
 *
 * <p>所有生成模块必须使用此服务而不是用字符串重建输出路径
 * 连接。解析的工作空间被规范化，限制为配置的输出根和
 * 当应用程序目录是符号链接或非目录项时拒绝。</p>
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

    /** 非 Spring 调用者和重点单元测试的兼容性构造函数。 */
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

    /** 解析工作区，而不强制编排代码构造持久性实体。 */
    public GenerationWorkspace resolve(Long appId, CodeGenTypeEnum codeGenType) {
        validateIdentity(appId, codeGenType);
        GenerationExecutionWorkspace executionWorkspace = executionScope.current(appId, codeGenType).orElse(null);
        if (executionWorkspace != null) {
            return executionWorkspace.workspace();
        }
        return resolveCanonical(appId, codeGenType);
    }

    /** 解析用户可见的规范应用程序工作空间，绕过执行范围。 */
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

    /** 解决一项持久任务所拥有的确切发布，拒绝陈旧/当前的不匹配。 */
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

    /** 为无法使用 ThreadLocal 范围的异步回调解析一个确切的任务/纪元工作区。 */
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
     * 仅当与一个规范完全匹配时才解析工件报告的应用程序工作区
     * 所提供应用程序的工作区布局。
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
     * 在不存在时创建规范应用程序目录并返回经过验证的工作区。
     *
     * <p>Creation 仅限于配置的输出根以下的一个直接子级。并发创作者
     * 通过重新验证存在检查和创建调用之间出现的条目来支持。</p>
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
                    // 另一个请求创建了相同的工作区；下面的验证决定它是否安全。
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
     * 描述执行工作空间物化器已经创建的工作空间路径。
     * 调用者不能使用此方法转义配置的输出根或执行子树。
     */
    public GenerationWorkspace resolveExecutionWorkspace(Long appId,
                                                          CodeGenTypeEnum codeGenType,
                                                          Path workspaceRoot) {
        return resolveExecutionWorkspace(appId, codeGenType, workspaceRoot, null);
    }

    /**
     * 描述一个执行目录，同时保留它是否是从现有的目录中播种的
     * 用户项目。目录是急切创建的，因此仅物理存在是不够的
     * 决定 CREATE 管道是否应该处理该任务。
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
