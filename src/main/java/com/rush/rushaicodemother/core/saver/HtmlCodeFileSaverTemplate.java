package com.rush.rushaicodemother.core.saver;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.HtmlCodeResult;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.security.workspace.GeneratedWorkspaceTrustPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/** 持久化单文件 HTML 生成结果。 */
@Component
public final class HtmlCodeFileSaverTemplate extends CodeFileSaverTemplate<HtmlCodeResult> {

    public HtmlCodeFileSaverTemplate(
            GenerationWorkspaceService generationWorkspaceService,
            WorkspaceFileSystemService workspaceFileSystemService,
            GeneratedWorkspaceTrustPolicy generatedWorkspaceTrustPolicy
    ) {
        super(HtmlCodeResult.class, generationWorkspaceService, workspaceFileSystemService,
                generatedWorkspaceTrustPolicy);
    }

    @Override
    protected CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.HTML;
    }

    /** 返回单文件 HTML 模式的完整文件声明。 */
    @Override
    protected List<GeneratedCodeFile> generatedFiles(HtmlCodeResult result) {
        return List.of(new GeneratedCodeFile("index.html", result.getHtmlCode()));
    }

    /** 校验 HTML 生成结果。 */
    @Override
    protected void validateInput(HtmlCodeResult result) {
        super.validateInput(result);
        if (StrUtil.isBlank(result.getHtmlCode())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HTML 代码不能为空");
        }
    }
}
