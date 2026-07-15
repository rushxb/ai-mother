package com.rush.rushaicodemother.core.saver;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.MultiFileCodeResult;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Persists the files produced by the native multi-file generation mode. */
@Component
public final class MultiFileCodeFileSaverTemplate extends CodeFileSaverTemplate<MultiFileCodeResult> {

    public MultiFileCodeFileSaverTemplate(
            GenerationWorkspaceService generationWorkspaceService,
            WorkspaceFileSystemService workspaceFileSystemService
    ) {
        super(MultiFileCodeResult.class, generationWorkspaceService, workspaceFileSystemService);
    }

    @Override
    protected CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.MULTI_FILE;
    }

    @Override
    protected void saveFiles(MultiFileCodeResult result, Path workspaceRoot) {
        synchronizeFile(workspaceRoot, "index.html", result.getHtmlCode());
        synchronizeFile(workspaceRoot, "style.css", result.getCssCode());
        synchronizeFile(workspaceRoot, "script.js", result.getJsCode());
    }

    @Override
    protected void validateInput(MultiFileCodeResult result) {
        super.validateInput(result);
        if (StrUtil.isBlank(result.getHtmlCode())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HTML 代码内容不能为空");
        }
    }
}
