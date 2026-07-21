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

/** Resolves the safe frontend project directory used by the local Dev Server. */
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

    /** Returns an existing Vue project directory containing a safe package manifest. */
    public Path locate(App app) {
        return locate(app, null);
    }

    /**
     * Resolves either the user-visible published workspace or an explicitly fenced generation
     * workspace. The latter is used only by task-scoped validation and never falls back to the
     * canonical pointer when the execution scope is missing.
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
