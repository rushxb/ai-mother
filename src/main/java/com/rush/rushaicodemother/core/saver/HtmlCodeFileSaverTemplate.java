package com.rush.rushaicodemother.core.saver;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.HtmlCodeResult;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** 持久化单文件 HTML 生成结果。 */
@Component
public final class HtmlCodeFileSaverTemplate extends CodeFileSaverTemplate<HtmlCodeResult> {

    public HtmlCodeFileSaverTemplate(
            GenerationWorkspaceService generationWorkspaceService,
            WorkspaceFileSystemService workspaceFileSystemService
    ) {
        super(HtmlCodeResult.class, generationWorkspaceService, workspaceFileSystemService);
    }

    @Override
    protected CodeGenTypeEnum getCodeType() {
        return CodeGenTypeEnum.HTML;
    }

    /**
 * 保存文件。
 *
 * @param result 待处理结果
 * @param workspaceRoot 工作区根
 */
    @Override
    protected void saveFiles(HtmlCodeResult result, Path workspaceRoot) {
        synchronizeFile(workspaceRoot, "index.html", result.getHtmlCode());
    }

    /**
 * 校验{@code ate}输入是否有效。
 *
 * @param result 待处理结果
 */
    @Override
    protected void validateInput(HtmlCodeResult result) {
        super.validateInput(result);
        if (StrUtil.isBlank(result.getHtmlCode())) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "HTML 代码不能为空");
        }
    }
}
