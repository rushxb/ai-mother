package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 从打包的 Go 模板引导规范后端生成工作区。 */
@Slf4j
@Component
public class BackendProjectTemplateBootstrapService {

    private final GenerationWorkspaceService generationWorkspaceService;
    private final ProjectTemplateBootstrapper templateBootstrapper;

    public BackendProjectTemplateBootstrapService(GenerationWorkspaceService generationWorkspaceService,
                                                  ProjectTemplateBootstrapper templateBootstrapper) {
        this.generationWorkspaceService = Objects.requireNonNull(
                generationWorkspaceService,
                "generationWorkspaceService must not be null"
        );
        this.templateBootstrapper = Objects.requireNonNull(templateBootstrapper, "templateBootstrapper must not be null");
    }

    /**
 * 返回{@code bootstrap}{@code If}{@code Necessary}。
 *
 * @param appId 应用编号
 * @param codeGenType 代码生成类型
 * @return 后端项目模板{@code Bootstrap}
 */
    public BootstrapResult bootstrapIfNecessary(Long appId, CodeGenTypeEnum codeGenType) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (appId == null || appId <= 0) {
            return BootstrapResult.skipped("", "", "invalid_app_id");
        }
        if (codeGenType != CodeGenTypeEnum.BACKEND_PROJECT && codeGenType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return BootstrapResult.skipped("", "", "unsupported_code_gen_type");
        }
        String templateId = ProjectTemplateCatalog.GO_SQLITE_BACKEND;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            GenerationWorkspace workspace = generationWorkspaceService.resolve(appId, codeGenType);
            ProjectTemplateBootstrapper.BootstrapOutcome outcome = templateBootstrapper.bootstrap(
                    templateId,
                    workspace.backendRootPath()
            );
            BootstrapResult result = new BootstrapResult(
                    outcome.bootstrapped(),
                    templateId,
                    outcome.projectPath().toString(),
                    outcome.fileCount(),
                    outcome.reason()
            );
            log.info(
                    "Backend template bootstrap completed: appId={}, codeGenType={}, templateId={}, bootstrapped={}, fileCount={}",
                    appId,
                    codeGenType,
                    templateId,
                    result.bootstrapped(),
                    result.fileCount()
            );
            return result;
        } catch (Exception exception) {
            log.warn(
                    "Backend template bootstrap failed: appId={}, codeGenType={}, templateId={}, error={}",
                    appId,
                    codeGenType,
                    templateId,
                    LogExceptionSanitizer.sanitize(exception)
            );
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "初始化后端项目模板失败，请稍后重试", exception);
        }
    }

    public record BootstrapResult(
            boolean bootstrapped,
            String templateId,
            String projectPath,
            int fileCount,
            String reason
    ) {

        public static BootstrapResult created(String templateId, String projectPath, int fileCount) {
            return new BootstrapResult(true, templateId, projectPath, fileCount, "");
        }

        public static BootstrapResult skipped(String templateId, String projectPath, String reason) {
            return new BootstrapResult(false, templateId, projectPath, 0, reason);
        }

        /**
 * 将当前对象转换为载荷。
 *
 * @return 载荷集合
 */
        public Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("bootstrapped", bootstrapped);
            payload.put("templateId", templateId);
            payload.put("projectPath", projectPath);
            payload.put("fileCount", fileCount);
            payload.put("reason", reason);
            return payload;
        }
    }
}
