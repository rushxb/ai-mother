package com.rush.rushaicodemother.orchestration.template.bootstrap;

import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

import java.util.Objects;

/** 模板初始化 adapter 的中立输入，不依赖 DAG 或具体 pipeline。 */
public record GenerationTemplateBootstrapRequest(
        Long appId,
        String userMessage,
        GenerationWorkspace workspace
) {

    public GenerationTemplateBootstrapRequest {
        if (appId == null) {
            throw new IllegalArgumentException("模板初始化缺少应用身份");
        }
        workspace = Objects.requireNonNull(workspace, "模板初始化工作区不能为空");
        if (!Objects.equals(appId, workspace.appId())) {
            throw new IllegalArgumentException("模板初始化应用与工作区身份不一致");
        }
        if (workspace.codeGenType() == null || workspace.canonicalRootPath() == null) {
            throw new IllegalArgumentException("模板初始化工作区缺少工程类型或规范根目录");
        }
        userMessage = userMessage == null ? "" : userMessage;
    }
}
