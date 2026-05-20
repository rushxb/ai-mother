package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

@Service
public class GenerationWorkspaceService {

    public static final Set<String> HIDDEN_FILE_NAMES = Set.of(
            ".git", ".idea", "node_modules", "dist", "target", ".DS_Store"
    );

    public static final Set<String> EDITABLE_EXTENSIONS = Set.of(
            "html", "css", "js", "ts", "jsx", "tsx", "vue", "json", "md", "txt", "xml", "svg", "yml", "yaml", "go", "sql", "mod", "sum"
    );

    public GenerationWorkspace resolve(App app, CodeGenTypeEnum codeGenType) {
        if (app == null || app.getId() == null || codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "生成工作区参数错误");
        }
        try {
            Path outputRoot = new File(AppConstant.CODE_OUTPUT_ROOT_DIR).getCanonicalFile().toPath();
            Path rootPath = outputRoot.resolve(codeGenType.getValue() + "_" + app.getId()).normalize();
            Path canonicalRootPath = Files.exists(rootPath) ? rootPath.toFile().getCanonicalFile().toPath() : rootPath.toAbsolutePath().normalize();
            if (!canonicalRootPath.startsWith(outputRoot)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "非法生成工作区路径");
            }
            Path frontendRootPath = codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT ? canonicalRootPath.resolve("frontend") : canonicalRootPath;
            Path backendRootPath = codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT || codeGenType == CodeGenTypeEnum.BACKEND_PROJECT
                    ? canonicalRootPath.resolve(codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT ? "backend" : "")
                    : null;
            return new GenerationWorkspace(
                    app.getId(),
                    codeGenType,
                    rootPath,
                    canonicalRootPath,
                    Files.isDirectory(canonicalRootPath),
                    frontendRootPath.normalize(),
                    backendRootPath == null ? null : backendRootPath.normalize(),
                    HIDDEN_FILE_NAMES,
                    EDITABLE_EXTENSIONS
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成工作区解析失败");
        }
    }
}
