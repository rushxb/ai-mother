package com.rush.rushaicodemother.core.saver;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.MultiFileCodeResult;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.security.workspace.GeneratedWorkspaceTrustPolicy;
import org.springframework.stereotype.Component;

import java.util.List;

/** 持久化原生多文件生成模式产出的文件。 */
@Component
public final class MultiFileCodeFileSaverTemplate extends CodeFileSaverTemplate<MultiFileCodeResult> {

    public MultiFileCodeFileSaverTemplate(
            GenerationWorkspaceService generationWorkspaceService,
            WorkspaceFileSystemService workspaceFileSystemService,
            GeneratedWorkspaceTrustPolicy generatedWorkspaceTrustPolicy
    ) {
        super(MultiFileCodeResult.class, generationWorkspaceService, workspaceFileSystemService,
                generatedWorkspaceTrustPolicy);
    }

    @Override
    protected CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.MULTI_FILE;
    }

    /** 返回原生多文件模式的完整文件声明。 */
    @Override
    protected List<GeneratedCodeFile> generatedFiles(MultiFileCodeResult result) {
        return List.of(
                new GeneratedCodeFile("index.html", result.getHtmlCode()),
                new GeneratedCodeFile("style.css", result.getCssCode()),
                new GeneratedCodeFile("script.js", result.getJsCode())
        );
    }

    /** 校验原生多文件生成结果。 */
    @Override
    protected void validateInput(MultiFileCodeResult result) {
        super.validateInput(result);
        if (StrUtil.isBlank(result.getHtmlCode())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HTML 代码内容不能为空");
        }
    }
}
