package com.rush.rushaicodemother.core.saver;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 持久化受支持代码生成结果的基础模板。
 *
 * <p>模板负责输入校验，并将工作区解析及有界的 UTF-8 原子写入交给专用基础设施。
 * 具体实现只需声明对应代码生成类型包含哪些文件。</p>
 *
 * @param <T> 支持的生成代码结果类型
 */
public abstract class CodeFileSaverTemplate<T> {

    private final Class<T> resultType;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final WorkspaceFileSystemService workspaceFileSystemService;

    protected CodeFileSaverTemplate(
            Class<T> resultType,
            GenerationWorkspaceService generationWorkspaceService,
            WorkspaceFileSystemService workspaceFileSystemService
    ) {
        this.resultType = Objects.requireNonNull(resultType, "resultType must not be null");
        this.generationWorkspaceService = Objects.requireNonNull(
                generationWorkspaceService,
                "generationWorkspaceService must not be null"
        );
        this.workspaceFileSystemService = Objects.requireNonNull(
                workspaceFileSystemService,
                "workspaceFileSystemService must not be null"
        );
    }

    /** 返回当前保存器负责的代码生成类型。 */
    public final CodeGenTypeEnum codeGenType() {
        return getCodeType();
    }

    /**
     * 将生成结果持久化到应用的标准工作区。
     *
     * @param result 生成代码结果
     * @param appId 应用程序标识符
     * @return 规范工作空间目录
     */
    public final File saveCode(Object result, Long appId) {
        return saveCode(result, appId, null);
    }

    /**
     * 将生成结果持久化到显式指定的执行工作区。异步模型回调使用此重载，
     * 因为回调线程不保证保留编排阶段的 ThreadLocal 状态。
     */
    public final File saveCode(Object result, Long appId, GenerationWorkspace explicitWorkspace) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 必须大于 0");
        }
        if (result == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "代码结果对象不能为空");
        }
        if (!resultType.isInstance(result)) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "代码结果类型与生成类型不匹配: " + codeGenType().getValue()
            );
        }

        T typedResult = resultType.cast(result);
        validateInput(typedResult);
        GenerationWorkspace workspace = explicitWorkspace == null
                ? generationWorkspaceService.prepare(appId, codeGenType())
                : requireMatchingWorkspace(explicitWorkspace, appId);
        saveFiles(typedResult, workspace.canonicalRootPath());
        return workspace.canonicalRootPath().toFile();
    }

    private GenerationWorkspace requireMatchingWorkspace(GenerationWorkspace workspace, Long appId) {
        if (!Objects.equals(appId, workspace.appId())
                || workspace.codeGenType() != codeGenType()
                || Files.isSymbolicLink(workspace.rootPath())
                || !Files.isDirectory(workspace.canonicalRootPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Generated code workspace does not match the active execution"
            );
        }
        return workspace;
    }

    /**
     * 通过受资源限制的工作区文件系统服务同步单个生成文件。
     * 内容为空时删除旧版本，避免重新生成后残留过期资源。
     */
    protected final void synchronizeFile(Path workspaceRoot, String relativePath, String content) {
        try {
            if (StrUtil.isBlank(content)) {
                workspaceFileSystemService.deleteFileIfExists(workspaceRoot, relativePath);
                return;
            }
            workspaceFileSystemService.writeUtf8Atomically(workspaceRoot, relativePath, content);
        } catch (IOException exception) {
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "生成代码文件保存失败: " + relativePath,
                    exception
            );
        }
    }

    /** 在执行任何文件系统变更前校验生成结果。 */
    protected void validateInput(T result) {
        if (result == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "代码结果对象不能为空");
        }
    }

    /** 持久化一个代码生成结果包含的全部文件。 */
    protected abstract void saveFiles(T result, Path workspaceRoot);

    /** 返回当前实现负责的代码生成类型。 */
    protected abstract CodeGenTypeEnum getCodeType();
}
