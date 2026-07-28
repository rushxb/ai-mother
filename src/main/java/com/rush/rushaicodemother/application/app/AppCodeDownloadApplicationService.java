package com.rush.rushaicodemother.application.app;

import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.ProjectDownloadService;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 应用代码下载模块。
 *
 * <p>集中完成授权和真实路径边界校验，禁止控制层自行拼接文件系统路径。</p>
 */
@Service
@RequiredArgsConstructor
public class AppCodeDownloadApplicationService {

    private final AppPersistenceService appPersistenceService;
    private final ProjectDownloadService projectDownloadService;
    private final AppAccessPolicy appAccessPolicy;
    private final GenerationWorkspaceService generationWorkspaceService;

    /**
 * 处理{@code download}。
 *
 * @param appId 应用编号
 * @param actor 操作发起人
 * @param response 响应对象
 */
    public void download(Long appId, User actor, HttpServletResponse response) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        App app = appPersistenceService.findActiveById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        appAccessPolicy.requireOwner(app, actor, "无权限下载该应用代码");

        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(codeGenType == null, ErrorCode.OPERATION_ERROR, "应用代码生成类型无效");
        GenerationWorkspace workspace = generationWorkspaceService.resolveCanonical(appId, codeGenType);
        ThrowUtils.throwIf(!workspace.exists(), ErrorCode.NOT_FOUND_ERROR, "应用代码不存在，请先生成代码");
        projectDownloadService.downloadProjectAsZip(
                workspace.canonicalRootPath().toString(),
                String.valueOf(appId),
                response
        );
    }
}
