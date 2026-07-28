package com.rush.rushaicodemother.service.workspace;

import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.core.builder.VueBuildResult;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.infrastructure.diagnostic.PublicDiagnosticSanitizer;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemException;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceFileMetadata;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService.WorkspaceTreeNode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.vo.AppCodeFileContentVO;
import com.rush.rushaicodemother.model.vo.AppCodeFileTreeVO;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 本地文件系统应用代码工作区实现。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LocalAppCodeWorkspaceService implements AppCodeWorkspaceService {

    private static final String INTERACTIVE_TEMP_FILE_PREFIX = ".app-code-";

    private final GenerationWorkspaceService generationWorkspaceService;
    private final VueProjectBuilder vueProjectBuilder;
    private final WorkspaceFileSystemService workspaceFileSystemService;
    private final WorkspaceFileSystemProperties workspaceFileSystemProperties;

    /**
 * 列出符合条件的文件。
 *
 * @param app 应用
 * @return 文件集合
 */
    @Override
    public List<AppCodeFileTreeVO> listFiles(App app) {
        GenerationWorkspace workspace = resolveWorkspace(app);
        if (!workspace.exists()) {
            return new ArrayList<>();
        }
        validateWorkspaceIdentity(workspace);
        try {
            return workspaceFileSystemService.listTree(
                            workspace.canonicalRootPath(),
                            workspaceFileSystemProperties.getMaxInteractiveTreeDepth(),
                            (relativePath, name, directory) -> !isHiddenName(workspace.hiddenFileNames(), name)
                    ).stream()
                    .map(this::toFileTreeView)
                    .toList();
        } catch (WorkspaceFileSystemException exception) {
            if (exception.reason() == WorkspaceFileSystemException.Reason.MISSING_DIRECTORY) {
                return new ArrayList<>();
            }
            throw mapFileSystemFailure(exception, "读取应用代码文件树");
        } catch (IOException exception) {
            log.warn("读取应用代码文件树失败，appId: {}", app.getId(), LogExceptionSanitizer.sanitize(exception));
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取应用代码文件树失败");
        }
    }

    /**
 * 读取文件。
 *
 * @param app 应用
 * @param filePath 文件路径
 * @return 文件
 */
    @Override
    public AppCodeFileContentVO readFile(App app, String filePath) {
        GenerationWorkspace workspace = requireExistingWorkspace(app);
        validateAccessibleRelativePath(workspace, filePath);
        WorkspaceFileMetadata file = resolveExistingFile(workspace, filePath);
        ensureEditableFile(workspace, file.fileName(), "该文件类型不支持在线预览编辑");
        try {
            String content = workspaceFileSystemService.readUtf8(
                    workspace.canonicalRootPath(),
                    file,
                    workspaceFileSystemProperties.getMaxInteractiveFileBytes()
            );
            AppCodeFileContentVO contentView = new AppCodeFileContentVO();
            contentView.setPath(file.relativePath());
            contentView.setName(file.fileName());
            contentView.setContent(content);
            contentView.setSize(file.size());
            contentView.setEditable(true);
            return contentView;
        } catch (WorkspaceFileSystemException exception) {
            throw mapFileSystemFailure(exception, "读取应用代码文件");
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "读取应用代码文件失败");
        }
    }

    /**
 * 保存文件。
 *
 * @param app 应用
 * @param filePath 文件路径
 * @param content 文件或消息内容
 */
    @Override
    public void saveFile(App app, String filePath, String content) {
        ThrowUtils.throwIf(content == null, ErrorCode.PARAMS_ERROR, "文件内容不能为空");
        long maxInteractiveFileBytes = workspaceFileSystemProperties.getMaxInteractiveFileBytes();
        ThrowUtils.throwIf(content.getBytes(StandardCharsets.UTF_8).length > maxInteractiveFileBytes,
                ErrorCode.OPERATION_ERROR, "文件内容过大，不支持在线保存");

        GenerationWorkspace workspace = requireExistingWorkspace(app);
        validateAccessibleRelativePath(workspace, filePath);
        WorkspaceFileMetadata originalFile = resolveExistingFile(workspace, filePath);
        ensureEditableFile(workspace, originalFile.fileName(), "该文件类型不支持在线编辑");

        String originalContent;
        WorkspaceFileMetadata savedFile;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            originalContent = workspaceFileSystemService.readUtf8(
                    workspace.canonicalRootPath(),
                    originalFile,
                    maxInteractiveFileBytes
            );
            if (originalContent.equals(content)) {
                return;
            }
            savedFile = workspaceFileSystemService.replaceUtf8Atomically(
                    workspace.canonicalRootPath(),
                    originalFile,
                    content,
                    maxInteractiveFileBytes
            );
        } catch (WorkspaceFileSystemException exception) {
            throw mapFileSystemFailure(exception, "保存应用代码文件");
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存应用代码文件失败");
        }

        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            rebuildIfRequired(workspace);
        } catch (BusinessException exception) {
            rollbackSavedFile(workspace, savedFile, originalContent);
            String publicDiagnostic = PublicDiagnosticSanitizer.sanitizeSingleLine(exception.getMessage(), 1_200);
            String publicMessage = "保存失败，代码未通过编译，已自动回退到上一次可用版本";
            if (!publicDiagnostic.isBlank()) {
                publicMessage += "：" + publicDiagnostic;
            }
            throw new BusinessException(exception.getCode(), publicMessage);
        } catch (Exception exception) {
            rollbackSavedFile(workspace, savedFile, originalContent);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    "保存失败，代码未通过编译，已自动回退到上一次可用版本");
        }
    }

    private GenerationWorkspace resolveWorkspace(App app) {
        ThrowUtils.throwIf(app == null || app.getId() == null, ErrorCode.PARAMS_ERROR, "应用不存在");
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(codeGenType == null, ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
        return generationWorkspaceService.resolveCanonical(app.getId(), codeGenType);
    }

    private GenerationWorkspace requireExistingWorkspace(App app) {
        GenerationWorkspace workspace = resolveWorkspace(app);
        ThrowUtils.throwIf(!workspace.exists(), ErrorCode.NOT_FOUND_ERROR, "应用代码不存在，请先生成代码");
        validateWorkspaceIdentity(workspace);
        return workspace;
    }

    private void validateWorkspaceIdentity(GenerationWorkspace workspace) {
        GenerationWorkspace current = generationWorkspaceService.resolveCanonical(
                workspace.appId(), workspace.codeGenType());
        ThrowUtils.throwIf(!workspace.canonicalRootPath().equals(current.canonicalRootPath()),
                ErrorCode.NO_AUTH_ERROR, "应用代码工作区路径异常");
    }

    /** 校验{@code ate}{@code Accessible}{@code Relative}路径是否有效。 */
    private void validateAccessibleRelativePath(GenerationWorkspace workspace, String filePath) {
        ThrowUtils.throwIf(filePath == null || filePath.isBlank(), ErrorCode.PARAMS_ERROR, "文件路径不能为空");
        String normalizedPath = filePath.replace('\\', '/');
        for (String segment : normalizedPath.split("/")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            ThrowUtils.throwIf("..".equals(segment), ErrorCode.NO_AUTH_ERROR, "非法文件路径");
            ThrowUtils.throwIf(isHiddenName(workspace.hiddenFileNames(), segment)
                            || segment.startsWith(INTERACTIVE_TEMP_FILE_PREFIX),
                    ErrorCode.NO_AUTH_ERROR, "禁止访问该文件");
        }
    }

    /** 根据当前上下文解析{@code Existing}文件。 */
    private WorkspaceFileMetadata resolveExistingFile(GenerationWorkspace workspace, String filePath) {
        try {
            return workspaceFileSystemService.resolveExistingFile(workspace.canonicalRootPath(), filePath);
        } catch (WorkspaceFileSystemException exception) {
            throw mapFileSystemFailure(exception, "解析应用代码文件");
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "解析应用代码文件失败");
        }
    }

    private AppCodeFileTreeVO toFileTreeView(WorkspaceTreeNode fileTreeNode) {
        AppCodeFileTreeVO view = new AppCodeFileTreeVO();
        view.setName(fileTreeNode.name());
        view.setPath(fileTreeNode.relativePath());
        view.setDirectory(fileTreeNode.directory());
        view.setSize(fileTreeNode.size());
        view.setChildren(fileTreeNode.children().stream().map(this::toFileTreeView).toList());
        return view;
    }

    private boolean isHiddenName(Set<String> hiddenNames, String fileName) {
        return hiddenNames.stream().anyMatch(hiddenName -> hiddenName.equalsIgnoreCase(fileName));
    }

    private void ensureEditableFile(GenerationWorkspace workspace, String fileName, String errorMessage) {
        int dotIndex = fileName.lastIndexOf('.');
        ThrowUtils.throwIf(dotIndex < 0 || dotIndex == fileName.length() - 1,
                ErrorCode.OPERATION_ERROR, errorMessage);
        String extension = fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        ThrowUtils.throwIf(!workspace.editableExtensions().contains(extension),
                ErrorCode.OPERATION_ERROR, errorMessage);
    }

    private void rebuildIfRequired(GenerationWorkspace workspace) {
        CodeGenTypeEnum codeGenType = workspace.codeGenType();
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT && codeGenType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return;
        }
        Path buildRoot = codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT
                ? workspace.frontendRootPath()
                : workspace.canonicalRootPath();
        VueBuildResult buildResult = vueProjectBuilder.buildProjectWithResult(buildRoot.toString());
        ThrowUtils.throwIf(!buildResult.success(), ErrorCode.SYSTEM_ERROR, buildResult.toPublicFailureSummary());
    }

    /** 处理回滚{@code Saved}文件。 */
    private void rollbackSavedFile(GenerationWorkspace workspace,
                                   WorkspaceFileMetadata savedFile,
                                   String originalContent) {
        try {
            workspaceFileSystemService.replaceUtf8Atomically(
                    workspace.canonicalRootPath(),
                    savedFile,
                    originalContent,
                    workspaceFileSystemProperties.getMaxInteractiveFileBytes()
            );
            rebuildIfRequired(workspace);
        } catch (WorkspaceFileSystemException exception) {
            if (exception.reason() == WorkspaceFileSystemException.Reason.FILE_CHANGED) {
                log.warn("构建失败后跳过回滚，文件已被并发更新，appId: {}, file: {}",
                        workspace.appId(), savedFile.relativePath());
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "保存后的构建失败，但文件已被其他请求更新，系统未覆盖最新内容，请刷新后重试");
            }
            throw rollbackFailure(workspace, savedFile, exception);
        } catch (Exception exception) {
            throw rollbackFailure(workspace, savedFile, exception);
        }
    }

    private BusinessException rollbackFailure(GenerationWorkspace workspace,
                                              WorkspaceFileMetadata savedFile,
                                              Exception exception) {
        log.error("保存失败后回滚文件异常，appId: {}, file: {}",
                workspace.appId(), savedFile.relativePath(), LogExceptionSanitizer.sanitize(exception));
        return new BusinessException(ErrorCode.SYSTEM_ERROR, "保存失败且自动回退异常，请联系管理员处理");
    }

    /** 将输入映射为文件{@code System}失败。 */
    private BusinessException mapFileSystemFailure(WorkspaceFileSystemException exception, String operation) {
        return switch (exception.reason()) {
            case INVALID_PATH -> new BusinessException(ErrorCode.NO_AUTH_ERROR, "非法文件路径");
            case UNSAFE_SYMBOLIC_LINK -> new BusinessException(ErrorCode.NO_AUTH_ERROR, "禁止通过符号链接访问文件");
            case MISSING_FILE, NOT_REGULAR_FILE -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "文件不存在");
            case FILE_TOO_LARGE, BYTE_LIMIT_EXCEEDED, FILE_LIMIT_EXCEEDED ->
                    new BusinessException(ErrorCode.OPERATION_ERROR, exception.getMessage());
            case FILE_CHANGED -> new BusinessException(ErrorCode.OPERATION_ERROR, "文件已发生变化，请刷新后重试");
            default -> {
                log.warn("{}失败", operation, LogExceptionSanitizer.sanitize(exception));
                yield new BusinessException(ErrorCode.SYSTEM_ERROR, operation + "失败");
            }
        };
    }
}
