package com.rush.rushaicodemother.service.dependency;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** 对可执行 Node.js 工具链命令的项目目录建立统一安全边界。 */
@Component
public class NodeProjectDirectoryValidator {

    /**
 * 校验{@code ate}是否有效。
 *
 * @param projectDirectory 项目目录
 * @return {@code ate}
 */
    public Validation validate(Path projectDirectory) {
        if (projectDirectory == null) {
            return Validation.invalid("项目目录不能为空");
        }
        Path normalizedProject = projectDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalizedProject)
                || !Files.isDirectory(normalizedProject, LinkOption.NOFOLLOW_LINKS)) {
            return Validation.invalid("项目目录不存在或不是安全的普通目录: " + normalizedProject);
        }
        Path packageJson = normalizedProject.resolve("package.json");
        if (Files.isSymbolicLink(packageJson)
                || !Files.isRegularFile(packageJson, LinkOption.NOFOLLOW_LINKS)) {
            return Validation.invalid("项目目录缺少安全的普通文件 package.json: " + normalizedProject);
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
