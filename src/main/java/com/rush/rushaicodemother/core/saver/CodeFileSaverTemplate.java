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
import java.nio.file.Path;
import java.util.Objects;

/**
 * Base template for persisting one supported generated-code result.
 *
 * <p>The template owns input validation and delegates workspace resolution and bounded, atomic UTF-8
 * writes to their dedicated infrastructure boundaries. Implementations only describe the files that
 * belong to one code-generation type.</p>
 *
 * @param <T> supported generated-code result type
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

    /** Returns the generation type handled by this saver. */
    public final CodeGenTypeEnum codeGenType() {
        return getCodeType();
    }

    /**
     * Persists a generated result into its canonical application workspace.
     *
     * @param result generated-code result
     * @param appId application identifier
     * @return canonical workspace directory
     */
    public final File saveCode(Object result, Long appId) {
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
        GenerationWorkspace workspace = generationWorkspaceService.prepare(appId, codeGenType());
        saveFiles(typedResult, workspace.canonicalRootPath());
        return workspace.canonicalRootPath().toFile();
    }

    /**
     * Synchronizes one generated file through the bounded workspace file-system service.
     * Blank optional content removes an earlier version so regeneration cannot retain stale assets.
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

    /** Validates the generated result before any file-system mutation occurs. */
    protected void validateInput(T result) {
        if (result == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "代码结果对象不能为空");
        }
    }

    /** Persists the files owned by one generated-code result. */
    protected abstract void saveFiles(T result, Path workspaceRoot);

    /** Returns the code-generation type handled by this implementation. */
    protected abstract CodeGenTypeEnum getCodeType();
}
