package com.rush.rushaicodemother.security.workspace;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** 对生成 Node.js 工作区建立统一的目录与内容信任校验 seam。 */
@Component
public class GeneratedNodeWorkspaceValidator {

    private final GeneratedWorkspaceTrustPolicy workspaceTrustPolicy;

    public GeneratedNodeWorkspaceValidator(GeneratedWorkspaceTrustPolicy workspaceTrustPolicy) {
        this.workspaceTrustPolicy = Objects.requireNonNull(
                workspaceTrustPolicy,
                "workspaceTrustPolicy must not be null"
        );
    }

    /**
     * 校验项目目录的文件系统边界与可执行内容信任策略。
     *
     * @param projectDirectory 项目目录
     * @return 包含真实项目路径或稳定拒绝原因的校验结果
     */
    public Validation validate(Path projectDirectory) {
        Validation directoryValidation = resolveProjectDirectory(projectDirectory);
        if (!directoryValidation.valid()) {
            return directoryValidation;
        }
        String rejectionReason = workspaceTrustPolicy.validateExecutableWorkspace(
                directoryValidation.projectPath());
        return rejectionReason.isEmpty()
                ? directoryValidation
                : Validation.invalid("生成工作区未通过安全校验: " + rejectionReason);
    }

    /**
     * 仅解析项目目录边界，供加锁与取消流程定位项目。
     * 取消必须在 manifest 被删除或工作区变为不可信后仍然可用。
     */
    public Validation resolveProjectDirectory(Path projectDirectory) {
        if (projectDirectory == null) {
            return Validation.invalid("项目目录不能为空");
        }
        Path normalizedProject = projectDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalizedProject)
                || !Files.isDirectory(normalizedProject, LinkOption.NOFOLLOW_LINKS)) {
            return Validation.invalid("项目目录不存在或不是安全的普通目录");
        }
        try {
            return Validation.valid(normalizedProject.toRealPath());
        } catch (IOException exception) {
            return Validation.invalid("无法解析项目目录，请检查目录权限和文件系统状态");
        }
    }

    public record Validation(boolean valid, Path projectPath, String errorDetail) {

        private static Validation valid(Path projectPath) {
            return new Validation(true, projectPath, null);
        }

        private static Validation invalid(String errorDetail) {
            return new Validation(false, null, errorDetail);
        }
    }
}
