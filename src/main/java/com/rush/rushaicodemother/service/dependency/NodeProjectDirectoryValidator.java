package com.rush.rushaicodemother.service.dependency;

import com.rush.rushaicodemother.security.workspace.GeneratedWorkspaceTrustPolicy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/** 对可执行 Node.js 工具链命令的项目目录建立统一安全边界。 */
@Component
public class NodeProjectDirectoryValidator {

    private final GeneratedWorkspaceTrustPolicy workspaceTrustPolicy;

    public NodeProjectDirectoryValidator(GeneratedWorkspaceTrustPolicy workspaceTrustPolicy) {
        this.workspaceTrustPolicy = Objects.requireNonNull(
                workspaceTrustPolicy,
                "workspaceTrustPolicy must not be null"
        );
    }

    /**
     * 校验项目目录的文件系统边界与依赖安装信任策略。
     *
     * @param projectDirectory 项目目录
     * @return 包含真实项目路径或稳定拒绝原因的校验结果
     */
    public Validation validate(Path projectDirectory) {
        Validation directoryValidation = resolveProjectDirectory(projectDirectory);
        if (!directoryValidation.valid()) {
            return directoryValidation;
        }
        String rejectionReason = workspaceTrustPolicy.validateDependencyInstallWorkspace(
                directoryValidation.projectPath());
        return rejectionReason.isEmpty()
                ? directoryValidation
                : Validation.invalid("项目依赖配置未通过安全校验: " + rejectionReason);
    }

    /**
     * 仅解析项目目录边界，供取消流程定位已经登记的进程。
     * 取消必须在 manifest 被删除或工作区变为不可信后仍然可用。
     */
    Validation resolveProjectDirectory(Path projectDirectory) {
        if (projectDirectory == null) {
            return Validation.invalid("项目目录不能为空");
        }
        Path normalizedProject = projectDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalizedProject)
                || !Files.isDirectory(normalizedProject, LinkOption.NOFOLLOW_LINKS)) {
            return Validation.invalid("项目目录不存在或不是安全的普通目录: " + normalizedProject);
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
