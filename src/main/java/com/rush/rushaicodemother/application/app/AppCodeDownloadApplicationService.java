package com.rush.rushaicodemother.application.app;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.AppService;
import com.rush.rushaicodemother.service.ProjectDownloadService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 应用代码下载模块。
 *
 * <p>集中完成授权和真实路径边界校验，禁止控制层自行拼接文件系统路径。</p>
 */
@Service
@RequiredArgsConstructor
public class AppCodeDownloadApplicationService {

    private final AppService appService;
    private final ProjectDownloadService projectDownloadService;
    private final AppAccessPolicy appAccessPolicy;

    public void download(Long appId, User actor, HttpServletResponse response) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        appAccessPolicy.requireOwner(app, actor, "无权限下载该应用代码");

        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(codeGenType == null, ErrorCode.OPERATION_ERROR, "应用代码生成类型无效");
        Path projectDirectory = resolveProjectDirectory(codeGenType, appId);
        projectDownloadService.downloadProjectAsZip(
                projectDirectory.toString(),
                String.valueOf(appId),
                response
        );
    }

    private Path resolveProjectDirectory(CodeGenTypeEnum codeGenType, Long appId) {
        Path configuredRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR).toAbsolutePath().normalize();
        String scopeName = codeGenType.getValue() + "_" + appId;
        Path declaredProjectDirectory = configuredRoot.resolve(scopeName).normalize();
        if (!declaredProjectDirectory.startsWith(configuredRoot)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "应用代码路径非法");
        }
        try {
            Path realRoot = configuredRoot.toRealPath();
            Path realProjectDirectory = declaredProjectDirectory.toRealPath();
            if (!realProjectDirectory.startsWith(realRoot) || !Files.isDirectory(realProjectDirectory)) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "应用代码路径超出允许目录");
            }
            return realProjectDirectory;
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用代码不存在，请先生成代码");
        }
    }
}