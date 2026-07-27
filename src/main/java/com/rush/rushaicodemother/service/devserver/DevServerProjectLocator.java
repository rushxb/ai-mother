package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemException;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** 解析本地开发服务器使用的安全前端项目目录。 */
@Component
public class DevServerProjectLocator {

    private final GenerationWorkspaceService generationWorkspaceService;
    private final WorkspaceFileSystemService workspaceFileSystemService;

    public DevServerProjectLocator(
            GenerationWorkspaceService generationWorkspaceService,
            WorkspaceFileSystemService workspaceFileSystemService
    ) {
        this.generationWorkspaceService = Objects.requireNonNull(
                generationWorkspaceService,
                "generationWorkspaceService must not be null"
        );
        this.workspaceFileSystemService = Objects.requireNonNull(
                workspaceFileSystemService,
                "workspaceFileSystemService must not be null"
        );
    }

    /** 返回包含安全包清单的现有 Vue 项目目录。 */
    public Path locate(App app) {
        return locate(app, null);
    }

    /**
     * 解决用户可见的已发布工作空间或显式隔离的生成问题
     * 工作区。后者仅用于任务范围的验证，并且永远不会回退到
     * 缺少执行范围时的规范指针。
     */
    public Path locate(App app, DevServerStartOptions startOptions) {
        CodeGenTypeEnum codeGenType = requireSupportedType(app);
        GenerationWorkspace workspace = resolveWorkspace(app.getId(), codeGenType, startOptions);
        Path projectDirectory = codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT
                ? workspace.frontendRootPath()
                : workspace.canonicalRootPath();

        try {
            if (!workspace.exists() || !workspaceFileSystemService.isDirectory(projectDirectory)) {
                throw projectNotFound(null);
            }
            workspaceFileSystemService.resolveExistingRegularFile(projectDirectory, "package.json");
            return projectDirectory.toAbsolutePath().normalize();
        } catch (WorkspaceFileSystemException exception) {
            if (isMissingOrUnsafe(exception.reason())) {
                throw projectNotFound(exception);
            }
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "无法校验 Dev Server 项目目录", exception);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "无法校验 Dev Server 项目目录", exception);
        }
    }

    private CodeGenTypeEnum requireSupportedType(App app) {
        if (app == null || app.getId() == null || app.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 必须大于 0");
        }
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT
                && codeGenType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "仅 Vue 项目支持 Dev Server 预览");
        }
        return codeGenType;
    }

    private GenerationWorkspace resolveWorkspace(Long appId,
                                                 CodeGenTypeEnum codeGenType,
                                                 DevServerStartOptions startOptions) {
        try {
            if (startOptions != null && startOptions.executionFence() != null) {
                return generationWorkspaceService.resolveExecution(
                        startOptions.executionFence(), appId, codeGenType);
            }
            return generationWorkspaceService.resolveCanonical(appId, codeGenType);
        } catch (BusinessException exception) {
            if (exception.getCode() == ErrorCode.NO_AUTH_ERROR.getCode()) {
                throw projectNotFound(exception);
            }
            throw exception;
        }
    }

    private boolean isMissingOrUnsafe(WorkspaceFileSystemException.Reason reason) {
        return reason == WorkspaceFileSystemException.Reason.MISSING_DIRECTORY
                || reason == WorkspaceFileSystemException.Reason.MISSING_FILE
                || reason == WorkspaceFileSystemException.Reason.NOT_REGULAR_FILE
                || reason == WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK;
    }

    private BusinessException projectNotFound(Throwable cause) {
        return new BusinessException(
                ErrorCode.NOT_FOUND_ERROR,
                "项目目录不存在或缺少安全的 package.json，请先生成代码",
                cause
        );
    }
}
