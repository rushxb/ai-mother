package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 将应用标识解析为受代码输出根目录约束的前端项目目录。
 */
@Component
public class DevServerProjectLocator {

    private final Path outputRoot;

    public DevServerProjectLocator() {
        this(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR));
    }

    DevServerProjectLocator(Path outputRoot) {
        if (outputRoot == null) {
            throw new IllegalArgumentException("代码输出根目录不能为空");
        }
        this.outputRoot = outputRoot.toAbsolutePath().normalize();
    }

    public Path locate(App app) {
        if (app == null || app.getId() == null || app.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        }
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT
                && codeGenType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "仅 Vue 项目支持 Dev Server 预览");
        }

        Path projectDirectory = outputRoot.resolve(codeGenType.getValue() + "_" + app.getId()).normalize();
        if (codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            projectDirectory = projectDirectory.resolve("frontend").normalize();
        }
        if (!projectDirectory.startsWith(outputRoot)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "项目目录超出代码输出根目录");
        }
        if (Files.isSymbolicLink(projectDirectory)
                || !Files.isDirectory(projectDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "项目目录不存在，请先生成代码");
        }

        Path realProjectDirectory = resolveRealProjectDirectory(projectDirectory);
        Path packageJson = realProjectDirectory.resolve("package.json");
        if (Files.isSymbolicLink(packageJson)
                || !Files.isRegularFile(packageJson, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "项目缺少安全的 package.json");
        }
        return realProjectDirectory;
    }

    private Path resolveRealProjectDirectory(Path projectDirectory) {
        try {
            Path realOutputRoot = outputRoot.toRealPath();
            Path realProjectDirectory = projectDirectory.toRealPath();
            if (!realProjectDirectory.startsWith(realOutputRoot)) {
                throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "项目目录超出代码输出根目录");
            }
            return realProjectDirectory;
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "无法解析项目目录", exception);
        }
    }
}