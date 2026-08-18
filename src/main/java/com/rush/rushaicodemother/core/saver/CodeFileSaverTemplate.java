package com.rush.rushaicodemother.core.saver;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.patch.GeneratedWorkspaceTrustPolicy;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

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
    private final GeneratedWorkspaceTrustPolicy generatedWorkspaceTrustPolicy;

    /**
     * 创建代码文件保存模板。
     *
     * @param resultType 结果类型
     * @param generationWorkspaceService 生成工作区服务
     * @param workspaceFileSystemService 有界工作区文件系统服务
     * @param generatedWorkspaceTrustPolicy 生成工作区信任策略
     */
    protected CodeFileSaverTemplate(
            Class<T> resultType,
            GenerationWorkspaceService generationWorkspaceService,
            WorkspaceFileSystemService workspaceFileSystemService,
            GeneratedWorkspaceTrustPolicy generatedWorkspaceTrustPolicy
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
        this.generatedWorkspaceTrustPolicy = Objects.requireNonNull(
                generatedWorkspaceTrustPolicy,
                "generatedWorkspaceTrustPolicy must not be null"
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
        List<GeneratedCodeFile> generatedFiles = requireTrustedFiles(typedResult);
        GenerationWorkspace workspace = explicitWorkspace == null
                ? generationWorkspaceService.prepare(appId, codeGenType())
                : requireMatchingWorkspace(explicitWorkspace, appId);
        persistFiles(generatedFiles, workspace.canonicalRootPath());
        return workspace.canonicalRootPath().toFile();
    }

    /** 校验并返回与本次执行匹配的工作区。 */
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
     * 通过受资源限制的工作区文件系统服务统一持久化已校验文件。
     * 内容为空白时删除旧版本，避免重新生成后残留过期资源。
     */
    private void persistFiles(List<GeneratedCodeFile> generatedFiles, Path workspaceRoot) {
        for (GeneratedCodeFile generatedFile : generatedFiles) {
            synchronizeFile(workspaceRoot, generatedFile);
        }
    }

    private void synchronizeFile(Path workspaceRoot, GeneratedCodeFile generatedFile) {
        try {
            if (StrUtil.isBlank(generatedFile.content())) {
                workspaceFileSystemService.deleteFileIfExists(
                        workspaceRoot, generatedFile.relativePath());
                return;
            }
            workspaceFileSystemService.writeUtf8Atomically(
                    workspaceRoot, generatedFile.relativePath(), generatedFile.content());
        } catch (IOException exception) {
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "生成代码文件保存失败: " + generatedFile.relativePath(),
                    exception
            );
        }
    }

    /** 在创建或修改工作区之前校验完整生成文件集合。 */
    private List<GeneratedCodeFile> requireTrustedFiles(T result) {
        List<GeneratedCodeFile> generatedFiles = generatedFiles(result);
        if (generatedFiles == null || generatedFiles.isEmpty()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成代码文件集合不能为空");
        }
        List<GeneratedCodeFile> fileSnapshot = new ArrayList<>(generatedFiles);
        Set<String> normalizedPaths = new HashSet<>();
        for (GeneratedCodeFile generatedFile : fileSnapshot) {
            if (generatedFile == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成代码文件不能为空");
            }
            String normalizedPath = generatedFile.relativePath()
                    .replace('\\', '/')
                    .toLowerCase(Locale.ROOT);
            if (!normalizedPaths.add(normalizedPath)) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "生成代码包含重复文件: " + generatedFile.relativePath());
            }
            String blocker = StrUtil.isBlank(generatedFile.content())
                    ? generatedWorkspaceTrustPolicy.validateDeletion(generatedFile.relativePath())
                    : generatedWorkspaceTrustPolicy.validate(
                            generatedFile.relativePath(), generatedFile.content());
            if (StrUtil.isNotBlank(blocker)) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "生成工作区内容不受信任: " + blocker);
            }
        }
        return List.copyOf(fileSnapshot);
    }

    /** 在执行任何文件系统变更前校验生成结果。 */
    protected void validateInput(T result) {
        if (result == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "代码结果对象不能为空");
        }
    }

    /** 声明一个代码生成结果包含的完整文件集合，不得直接修改文件系统。 */
    protected abstract List<GeneratedCodeFile> generatedFiles(T result);

    /** 返回当前实现负责的代码生成类型。 */
    protected abstract CodeGenTypeEnum getCodeType();
}
